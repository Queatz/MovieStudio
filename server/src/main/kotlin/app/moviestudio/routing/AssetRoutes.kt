package app.moviestudio.routing

import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.AssetVersion
import app.moviestudio.GenerationSetup
import app.moviestudio.Job
import app.moviestudio.JobStatus
import app.moviestudio.JobType
import app.moviestudio.database.AssetRepository
import app.moviestudio.database.JobRepository
import app.moviestudio.service.AiJobPayload
import app.moviestudio.service.AIGenerationService
import app.moviestudio.storage.OssService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class UploadUrlRequest(val objectKey: String)

@Serializable
data class UploadUrlResponse(
    val uploadUrl: String,
    val objectKey: String,
    /** Long-lived signed GET URL clients should persist as the asset's read URL. */
    val downloadUrl: String,
    /** The exact Content-Type the PUT must send; it is part of the upload URL's signature. */
    val contentType: String
)

@Serializable
data class RestoreVersionRequest(val versionIndex: Int)

@Serializable
data class ClipAudioRequest(val startSeconds: Double, val endSeconds: Double, val name: String = "")

private val payloadJson = Json { ignoreUnknownKeys = true }

/**
 * Builds and enqueues the AI generation job that (re)generates media for an existing asset from
 * its description. Used by both the "Generate" button on description-only assets and the
 * "Regenerate" action on assets that already have media.
 */
private fun enqueueAssetGeneration(asset: Asset): Job {
    val baseSetup: GenerationSetup? = asset.generationConfig?.let {
        runCatching { payloadJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
    }
    val prompt = asset.description?.takeIf { it.isNotBlank() }
        ?: asset.aiPrompt?.takeIf { it.isNotBlank() }
        ?: baseSetup?.prompt.orEmpty()
    val kind = when (asset.type) {
        AssetType.IMAGE -> "image"
        AssetType.MUSIC -> "music"
        AssetType.VOICE -> "tts"
        AssetType.AUDIO -> "sfx"
        else -> "video"
    }
    val setup = (baseSetup ?: GenerationSetup(kind = kind)).let {
        it.copy(
            kind = if (it.kind.isBlank()) kind else it.kind,
            prompt = prompt.ifBlank { it.prompt },
            theme = if (asset.type == AssetType.MUSIC && it.theme.isBlank()) prompt else it.theme,
            voice = if (asset.type == AssetType.VOICE && it.voice.isBlank()) (asset.voice ?: "Cherry") else it.voice,
            durationSeconds = if (it.durationSeconds > 0) it.durationSeconds else asset.durationSeconds
        )
    }
    val payload = AiJobPayload(setup = setup, assetId = asset.id, targetType = asset.type.name)
    val job = Job(
        id = UUID.randomUUID().toString(),
        movieId = asset.movieId ?: "",
        type = JobType.AI_GEN,
        status = JobStatus.PENDING,
        payload = payloadJson.encodeToString(AiJobPayload.serializer(), payload),
        resultUrl = null,
        label = "${asset.type.name.lowercase().replaceFirstChar { it.uppercase() }}: ${prompt.take(60)}",
        createdAt = System.currentTimeMillis()
    )
    return JobRepository.insert(job)
}

fun Route.assetRoutes() {
    route("/api/assets") {
        get {
            try {
                val movieId = call.request.queryParameters["movieId"]
                val assets = AssetRepository.queryByMovieId(movieId)
                call.respond(assets)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post("/upload-url") {
            try {
                val request = call.receive<UploadUrlRequest>()
                val uploadUrl = OssService.generatePreSignedUploadUrl(request.objectKey)
                call.respond(
                    UploadUrlResponse(
                        uploadUrl = uploadUrl,
                        objectKey = request.objectKey,
                        downloadUrl = OssService.downloadUrl(request.objectKey),
                        contentType = OssService.uploadContentType(request.objectKey)
                    )
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val asset = call.receive<Asset>()
                val stamped = if (asset.createdAt == 0L) asset.copy(createdAt = System.currentTimeMillis()) else asset
                val saved = AssetRepository.insert(stamped)
                call.respond(HttpStatusCode.Created, saved)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        get("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing id")
                val asset = AssetRepository.getById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Asset not found")
                call.respond(asset)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val asset = call.receive<Asset>()
                val assetWithId = if (asset.id != id) asset.copy(id = id) else asset
                val updated = AssetRepository.update(assetWithId)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                AssetRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post("/{id}/transcript") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val asset = AssetRepository.getById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Asset not found")
                val updated = AIGenerationService.generateTranscript(asset)
                call.respond(HttpStatusCode.OK, updated)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Generates (or regenerates) the media for this asset from its description, as an async
        // job. Previous media is pushed onto the asset's restorable version history.
        post("/{id}/generate") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val asset = AssetRepository.getById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Asset not found")
                if ((asset.description ?: asset.aiPrompt).isNullOrBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Asset has no description to generate from")
                }
                val job = enqueueAssetGeneration(asset)
                call.respond(HttpStatusCode.Accepted, job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Restores a previous version of the asset's media from its history.
        post("/{id}/restore") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val request = call.receive<RestoreVersionRequest>()
                val asset = AssetRepository.getById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Asset not found")
                val version = asset.history.getOrNull(request.versionIndex)
                    ?: return@post call.respond(HttpStatusCode.BadRequest, "No such version")

                val newHistory = asset.history.toMutableList()
                newHistory.removeAt(request.versionIndex)
                if (asset.ossUrl.isNotBlank()) {
                    newHistory.add(
                        0,
                        AssetVersion(
                            ossUrl = asset.ossUrl,
                            durationSeconds = asset.durationSeconds,
                            createdAt = System.currentTimeMillis(),
                            prompt = asset.aiPrompt
                        )
                    )
                }
                val restored = asset.copy(
                    ossUrl = version.ossUrl,
                    durationSeconds = version.durationSeconds,
                    history = newHistory
                )
                AssetRepository.update(restored)
                call.respond(HttpStatusCode.OK, restored)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Extracts the audio track of this (video) asset into a new sound asset, as an async job.
        post("/{id}/extract-audio") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val asset = AssetRepository.getById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Asset not found")
                if (asset.ossUrl.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Asset has no media to extract audio from")
                }
                val payload = AiJobPayload(
                    setup = GenerationSetup(
                        kind = "extract-audio",
                        prompt = "Audio from: ${(asset.description ?: asset.aiPrompt).orEmpty()}".trim()
                    ),
                    sourceUrl = asset.ossUrl
                )
                val job = Job(
                    id = UUID.randomUUID().toString(),
                    movieId = asset.movieId ?: "",
                    type = JobType.AI_GEN,
                    status = JobStatus.PENDING,
                    payload = payloadJson.encodeToString(AiJobPayload.serializer(), payload),
                    resultUrl = null,
                    label = "Extract audio: ${(asset.description ?: "asset").take(50)}",
                    createdAt = System.currentTimeMillis()
                )
                JobRepository.insert(job)
                call.respond(HttpStatusCode.Accepted, job)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }

        // Clips a window out of a sound asset into a new sound-effect asset (metadata-only:
        // playback and rendering honor the stored source offset).
        post("/{id}/clip") {
            try {
                val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Missing id")
                val request = call.receive<ClipAudioRequest>()
                val asset = AssetRepository.getById(id)
                    ?: return@post call.respond(HttpStatusCode.NotFound, "Asset not found")
                if (asset.ossUrl.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Asset has no media to clip")
                }
                val start = request.startSeconds.coerceAtLeast(0.0)
                val end = request.endSeconds
                if (end <= start) {
                    return@post call.respond(HttpStatusCode.BadRequest, "endSeconds must be greater than startSeconds")
                }
                val name = request.name.ifBlank { "Clip of ${(asset.description ?: "sound").take(40)}" }
                val clipped = Asset(
                    id = UUID.randomUUID().toString(),
                    type = AssetType.AUDIO,
                    ossUrl = asset.ossUrl,
                    durationSeconds = end - start,
                    movieId = asset.movieId,
                    tags = (asset.tags + "sound-effect" + "clipped").distinct(),
                    aiPrompt = asset.aiPrompt,
                    description = name,
                    sourceOffsetSeconds = asset.sourceOffsetSeconds + start,
                    createdAt = System.currentTimeMillis()
                )
                AssetRepository.insert(clipped)
                call.respond(HttpStatusCode.Created, clipped)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }

    route("/api/library") {
        get {
            try {
                val movieId = call.request.queryParameters["movieId"]
                val typeStr = call.request.queryParameters["type"]
                val type = typeStr?.let { raw ->
                    val normalized = raw.uppercase()
                    // "VOICE" is an accepted alias for the VO (voiceover) asset type.
                    if (normalized == "VOICE") {
                        AssetType.VOICE
                    } else {
                        try {
                            AssetType.valueOf(normalized)
                        } catch (ex: IllegalArgumentException) {
                            return@get call.respond(HttpStatusCode.BadRequest, "Invalid asset type: $raw")
                        }
                    }
                }
                val tagsStr = call.request.queryParameters["tags"]
                val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

                val assets = AssetRepository.queryLibrary(movieId, type, tags)
                call.respond(assets)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
