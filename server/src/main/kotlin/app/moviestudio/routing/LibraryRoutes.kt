package app.moviestudio.routing

import app.moviestudio.Character
import app.moviestudio.Scene
import app.moviestudio.VisualStyle
import app.moviestudio.VoiceOptions
import app.moviestudio.database.CharacterRepository
import app.moviestudio.database.SceneRepository
import app.moviestudio.database.VisualStyleRepository
import app.moviestudio.database.VoiceCloneRepository
import app.moviestudio.database.VoiceDesignRepository
import app.moviestudio.service.AIGenerationService
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class CreateVoiceCloneRequest(val name: String, val audioUrl: String)

@Serializable
data class CreateVoiceDesignRequest(val name: String, val description: String)

@Serializable
data class SampleVoiceRequest(val voiceId: String, val text: String = "")

@Serializable
data class SampleVoiceResponse(val url: String)

fun Route.libraryRoutes() {
    // ---------------------------------------------------------------- saved characters library
    route("/api/characters") {
        get {
            try {
                call.respond(CharacterRepository.listAll())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val received = call.receive<Character>()
                val character = received.copy(
                    id = received.id.ifBlank { UUID.randomUUID().toString() },
                    referenceImages = received.referenceImages.take(Character.MAX_REFERENCE_IMAGES),
                    createdAt = if (received.createdAt == 0L) System.currentTimeMillis() else received.createdAt
                )
                if (character.name.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Character name must not be blank")
                }
                call.respond(HttpStatusCode.Created, CharacterRepository.insert(character))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val received = call.receive<Character>()
                val character = received.copy(
                    id = id,
                    referenceImages = received.referenceImages.take(Character.MAX_REFERENCE_IMAGES)
                )
                call.respond(HttpStatusCode.OK, CharacterRepository.update(character))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                CharacterRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }

    // -------------------------------------------------------------------- saved scenes library
    route("/api/scenes") {
        get {
            try {
                call.respond(SceneRepository.listAll())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val received = call.receive<Scene>()
                val scene = received.copy(
                    id = received.id.ifBlank { UUID.randomUUID().toString() },
                    referenceImages = received.referenceImages.take(Scene.MAX_REFERENCE_IMAGES),
                    createdAt = if (received.createdAt == 0L) System.currentTimeMillis() else received.createdAt
                )
                if (scene.name.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Scene name must not be blank")
                }
                call.respond(HttpStatusCode.Created, SceneRepository.insert(scene))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val received = call.receive<Scene>()
                val scene = received.copy(
                    id = id,
                    referenceImages = received.referenceImages.take(Scene.MAX_REFERENCE_IMAGES)
                )
                call.respond(HttpStatusCode.OK, SceneRepository.update(scene))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                SceneRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }

    // ---------------------------------------------------------------- saved visual styles library
    route("/api/styles") {
        get {
            try {
                call.respond(VisualStyleRepository.listAll())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        post {
            try {
                val received = call.receive<VisualStyle>()
                val style = received.copy(
                    id = received.id.ifBlank { UUID.randomUUID().toString() },
                    name = received.name.trim(),
                    style = received.style.trim(),
                    createdAt = if (received.createdAt == 0L) System.currentTimeMillis() else received.createdAt
                )
                if (style.name.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Style name must not be blank")
                }
                if (style.style.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Style text must not be blank")
                }
                call.respond(HttpStatusCode.Created, VisualStyleRepository.insert(style))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        put("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@put call.respond(HttpStatusCode.BadRequest, "Missing id")
                val received = call.receive<VisualStyle>()
                val style = received.copy(
                    id = id,
                    name = received.name.trim(),
                    style = received.style.trim()
                )
                if (style.name.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, "Style name must not be blank")
                }
                if (style.style.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, "Style text must not be blank")
                }
                call.respond(HttpStatusCode.OK, VisualStyleRepository.update(style))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                VisualStyleRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }

    // --------------------------------------------------------------------------- voice library
    route("/api/voice") {
        // The full Voice Library: built-in default voices (with spoken languages) plus the
        // user's cloned and designed voices.
        get("/options") {
            try {
                call.respond(
                    VoiceOptions(
                        presets = AIGenerationService.listVoicePresets(),
                        clones = VoiceCloneRepository.listAll(),
                        designs = VoiceDesignRepository.listAll()
                    )
                )
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        // Synthesizes a short spoken preview ("sample") of a voice and returns a playable URL.
        post("/sample") {
            try {
                val request = call.receive<SampleVoiceRequest>()
                if (request.voiceId.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "voiceId is required")
                }
                val url = AIGenerationService.sampleVoice(request.voiceId, request.text)
                call.respond(SampleVoiceResponse(url))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        // Enrolls a new cloned voice (Qwen voice cloning, China mainland) and persists it.
        post("/clones") {
            try {
                val request = call.receive<CreateVoiceCloneRequest>()
                if (request.name.isBlank() || request.audioUrl.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Name and audioUrl are required")
                }
                val clone = AIGenerationService.createVoiceClone(request.name, request.audioUrl)
                call.respond(HttpStatusCode.Created, clone)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/clones/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                VoiceCloneRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        // Designs a new voice from a natural-language description (CosyVoice Voice Design).
        post("/designs") {
            try {
                val request = call.receive<CreateVoiceDesignRequest>()
                if (request.name.isBlank() || request.description.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, "Name and description are required")
                }
                val design = AIGenerationService.createVoiceDesign(request.name, request.description)
                call.respond(HttpStatusCode.Created, design)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
        delete("/designs/{id}") {
            try {
                val id = call.parameters["id"] ?: return@delete call.respond(HttpStatusCode.BadRequest, "Missing id")
                VoiceDesignRepository.delete(id)
                call.respond(HttpStatusCode.OK, mapOf("deleted" to true))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "Internal Server Error")
            }
        }
    }
}
