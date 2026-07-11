package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.routing.movieRoutes
import app.moviestudio.routing.assetRoutes
import app.moviestudio.routing.documentRoutes
import app.moviestudio.routing.fontRoutes
import app.moviestudio.routing.generationRoutes
import app.moviestudio.routing.issueRoutes
import app.moviestudio.routing.jobRoutes
import app.moviestudio.routing.libraryRoutes
import app.moviestudio.routing.speechRoutes
import app.moviestudio.routing.tipRoutes
import app.moviestudio.job.JobQueueWorker
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.GoogleFontsConfig
import app.moviestudio.service.GoogleFontsService
import app.moviestudio.service.JobRecoveryService
import app.moviestudio.service.RenderUploadRetryService
import app.moviestudio.service.QwenAIService
import app.moviestudio.service.QwenConfig
import app.moviestudio.storage.OssService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

@Serializable
data class HealthResponse(val status: String)

fun main() {
    embeddedServer(
        Netty,
        port = 8080,
        host = "0.0.0.0",
        watchPaths = emptyList(), // Do not watch any paths for changes
        module = Application::module
    )
        .start(wait = true)
}

fun Application.module() {
    val logger = LoggerFactory.getLogger("Application")
    try {
        ArangoDatabase.init()
    } catch (e: Exception) {
        logger.error("Could not initialize ArangoDB on startup: ${e.message}", e)
    }

    try {
        OssService.ensureBucketExists()
    } catch (e: Exception) {
        logger.error("Could not ensure OSS bucket exists on startup: ${e.message}", e)
    }

    try {
        OssService.ensureBucketCors()
    } catch (e: Exception) {
        logger.error("Could not configure OSS bucket CORS on startup: ${e.message}", e)
    }

    QwenConfig.logStatus()
    AIGenerationService.setInstance(QwenAIService)
    logger.info("Using QwenAIService for AI generation jobs.")

    // Warm the Google Fonts catalog in the background. It is fetched from the Developer API at
    // most once per TTL window (7 days by default) and cached in the database in between, so a
    // restart never re-hits the API needlessly.
    GoogleFontsConfig.logStatus()
    GoogleFontsService.init(this)

    // Hand the queue worker the application scope so it can be (re)started on demand. It is not
    // started here: the worker only runs while there is work, i.e. it is kicked off when a job is
    // started or a recovered job is re-enqueued, and stops itself once the queue drains.
    JobQueueWorker.init(this)

    // Reconcile jobs left RUNNING by a previous (crashed/restarted) process: resumable Model Studio
    // async tasks are re-enqueued (which starts the worker), the rest are cleaned up.
    try {
        JobRecoveryService.start(this)
    } catch (e: Exception) {
        logger.error("Could not start interrupted-job recovery on startup: ${e.message}", e)
    }

    // Periodically re-upload any rendered movies whose upload to OSS previously failed. The render
    // files are persisted locally, so a transient storage outage never loses a finished movie. Like
    // the job worker this is demand-driven: init() hands it the scope, start() picks up anything
    // left over from a previous run and it stops itself once nothing is pending.
    RenderUploadRetryService.init(this)
    try {
        RenderUploadRetryService.start(this)
    } catch (e: Exception) {
        logger.error("Could not start render-upload retry worker on startup: ${e.message}", e)
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = true
        })
    }

    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 15.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    routing {
        get("/") {
            call.respondText(sayHello("Ktor"))
        }
        get("/api/health") {
            call.respond(HealthResponse("OK"))
        }
        movieRoutes()
        assetRoutes()
        documentRoutes()
        jobRoutes()
        generationRoutes()
        libraryRoutes()
        speechRoutes()
        tipRoutes()
        issueRoutes()
        fontRoutes()
    }
}
