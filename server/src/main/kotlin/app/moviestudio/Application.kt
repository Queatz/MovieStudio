package app.moviestudio

import app.moviestudio.database.ArangoDatabase
import app.moviestudio.routing.movieRoutes
import app.moviestudio.routing.assetRoutes
import app.moviestudio.routing.generationRoutes
import app.moviestudio.routing.jobRoutes
import app.moviestudio.routing.libraryRoutes
import app.moviestudio.job.JobQueueWorker
import app.moviestudio.service.AIGenerationService
import app.moviestudio.service.MockAIService
import app.moviestudio.service.QwenAIService
import app.moviestudio.service.QwenConfig
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

    QwenConfig.logStatus()
    // Tests (and local dev) can force the offline mock AI regardless of configured credentials.
    val forceMockAi = System.getProperty("moviestudio.forceMockAI") == "true" ||
        System.getenv("MOVIESTUDIO_FORCE_MOCK_AI") == "true"
    if (QwenConfig.isConfigured && !forceMockAi) {
        AIGenerationService.setInstance(QwenAIService)
        logger.info("Using QwenAIService for AI generation jobs.")
    } else {
        AIGenerationService.setInstance(MockAIService)
        logger.info("Qwen not configured (or mock forced); using MockAIService for AI generation jobs.")
    }

    JobQueueWorker.start(this)

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
        jobRoutes()
        generationRoutes()
        libraryRoutes()
    }
}
