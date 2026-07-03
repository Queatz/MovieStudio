package app.moviestudio

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Response returned by the server when requesting a pre-signed upload URL for an object key. */
data class UploadUrlResponse(val uploadUrl: String, val objectKey: String)

object NetworkService {
    private val client = getHttpFetchClient()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    private fun url(path: String): String {
        val base = getBaseUrl().removeSuffix("/")
        val p = if (path.startsWith("/")) path else "/$path"
        return "$base$p"
    }

    /** The WebSocket URL streaming job progress events for the given movie. */
    fun jobEventsWsUrl(movieId: String): String {
        val base = getBaseUrl().removeSuffix("/")
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        return "$base/api/jobs/ws?movieId=$movieId"
    }

    // ------------------------------------------------------------------------------- movies

    suspend fun getMovies(): List<Film> {
        val responseText = client.get(url("/api/movies"))
        return json.decodeFromString(ListSerializer(Film.serializer()), responseText)
    }

    suspend fun createMovie(movie: Film): Film {
        val body = json.encodeToString(Film.serializer(), movie)
        val responseText = client.post(url("/api/movies"), body)
        return json.decodeFromString(Film.serializer(), responseText)
    }

    suspend fun updateMovie(movie: Film): Film {
        val body = json.encodeToString(Film.serializer(), movie)
        val responseText = client.put(url("/api/movies/${movie.id}"), body)
        return json.decodeFromString(Film.serializer(), responseText)
    }

    suspend fun deleteMovie(id: String) {
        client.delete(url("/api/movies/$id"))
    }

    suspend fun getTimeline(movieId: String): FilmTimeline {
        val responseText = client.get(url("/api/movies/$movieId/timeline"))
        return json.decodeFromString(FilmTimeline.serializer(), responseText)
    }

    suspend fun createTrack(movieId: String, track: Track): Track {
        val body = json.encodeToString(Track.serializer(), track)
        val responseText = client.post(url("/api/movies/$movieId/tracks"), body)
        return json.decodeFromString(Track.serializer(), responseText)
    }

    suspend fun deleteTrack(movieId: String, trackId: String) {
        client.delete(url("/api/movies/$movieId/tracks/$trackId"))
    }

    suspend fun createClip(movieId: String, clip: Clip): Clip {
        val body = json.encodeToString(Clip.serializer(), clip)
        val responseText = client.post(url("/api/movies/$movieId/clips"), body)
        return json.decodeFromString(Clip.serializer(), responseText)
    }

    suspend fun updateClip(movieId: String, clip: Clip): Clip {
        val body = json.encodeToString(Clip.serializer(), clip)
        val responseText = client.put(url("/api/movies/$movieId/clips/${clip.id}"), body)
        return json.decodeFromString(Clip.serializer(), responseText)
    }

    suspend fun deleteClip(movieId: String, clipId: String) {
        client.delete(url("/api/movies/$movieId/clips/$clipId"))
    }

    /** Queues server-side skeleton generation (Qwen plans placeholder items on the timeline). */
    suspend fun generateSkeleton(movieId: String, prompt: String, atSeconds: Double): Job {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("atSeconds", atSeconds)
        }.toString()
        val responseText = client.post(url("/api/movies/$movieId/skeleton"), body)
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Kicks off the final movie render job on the server. */
    suspend fun startRender(movieId: String): Job {
        val responseText = client.post(url("/api/movies/$movieId/render"), "{}")
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Every completed render of the movie, newest first. */
    suspend fun getRenders(movieId: String): List<RenderRecord> {
        val responseText = client.get(url("/api/movies/$movieId/renders"))
        return json.decodeFromString(ListSerializer(RenderRecord.serializer()), responseText)
    }

    // ------------------------------------------------------------------------------- assets

    suspend fun getLibraryAssets(movieId: String?, type: AssetType?): List<Asset> {
        val params = mutableListOf<String>()
        if (movieId != null) params.add("movieId=$movieId")
        if (type != null) params.add("type=${type.name.lowercase()}")
        val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val responseText = client.get(url("/api/library$queryString"))
        return json.decodeFromString(ListSerializer(Asset.serializer()), responseText)
    }

    suspend fun getAsset(assetId: String): Asset {
        val responseText = client.get(url("/api/assets/$assetId"))
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    suspend fun createAsset(asset: Asset): Asset {
        val body = json.encodeToString(Asset.serializer(), asset)
        val responseText = client.post(url("/api/assets"), body)
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    suspend fun updateAsset(asset: Asset): Asset {
        val body = json.encodeToString(Asset.serializer(), asset)
        val responseText = client.put(url("/api/assets/${asset.id}"), body)
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    suspend fun deleteAsset(assetId: String) {
        client.delete(url("/api/assets/$assetId"))
    }

    /** Requests a pre-signed URL that the client can PUT a file to directly (device uploads). */
    suspend fun requestUploadUrl(objectKey: String): UploadUrlResponse {
        val body = buildJsonObject { put("objectKey", objectKey) }.toString()
        val responseText = client.post(url("/api/assets/upload-url"), body)
        val obj = json.parseToJsonElement(responseText).jsonObject
        return UploadUrlResponse(
            uploadUrl = obj["uploadUrl"]?.jsonPrimitive?.content.orEmpty(),
            objectKey = obj["objectKey"]?.jsonPrimitive?.content.orEmpty()
        )
    }

    suspend fun generateTranscript(assetId: String): Asset {
        val responseText = client.post(url("/api/assets/$assetId/transcript"))
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    /** Generates (or regenerates) an asset's media from its description, as an async job. */
    suspend fun generateAssetMedia(assetId: String): Job {
        val responseText = client.post(url("/api/assets/$assetId/generate"))
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Restores a previous version from the asset's history. */
    suspend fun restoreAssetVersion(assetId: String, versionIndex: Int): Asset {
        val body = buildJsonObject { put("versionIndex", versionIndex) }.toString()
        val responseText = client.post(url("/api/assets/$assetId/restore"), body)
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    /** Extracts the audio track of a video asset into a new sound asset (async job). */
    suspend fun extractAudio(assetId: String): Job {
        val responseText = client.post(url("/api/assets/$assetId/extract-audio"))
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Clips a window out of a sound asset into a new sound-effect asset. */
    suspend fun clipAudio(assetId: String, startSeconds: Double, endSeconds: Double, name: String): Asset {
        val body = buildJsonObject {
            put("startSeconds", startSeconds)
            put("endSeconds", endSeconds)
            put("name", name)
        }.toString()
        val responseText = client.post(url("/api/assets/$assetId/clip"), body)
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    // ----------------------------------------------------------------------------- generation

    /** Queues an AI media generation (video/image/music/tts/sfx) described by [setup]. */
    suspend fun generateMedia(movieId: String?, setup: GenerationSetup, assetId: String? = null): Job {
        val body = buildJsonObject {
            if (movieId != null) put("movieId", movieId)
            put("setup", json.encodeToJsonElement(GenerationSetup.serializer(), setup))
            if (assetId != null) put("assetId", assetId)
        }.toString()
        val responseText = client.post(url("/api/generate/media"), body)
        return json.decodeFromString(Job.serializer(), responseText)
    }

    suspend fun generateLyrics(prompt: String, movieTitle: String): String {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("movieTitle", movieTitle)
        }.toString()
        val responseText = client.post(url("/api/generate/lyrics"), body)
        return json.parseToJsonElement(responseText).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
    }

    suspend fun generateTheme(prompt: String, movieTitle: String): String {
        val body = buildJsonObject {
            put("prompt", prompt)
            put("movieTitle", movieTitle)
        }.toString()
        val responseText = client.post(url("/api/generate/theme"), body)
        return json.parseToJsonElement(responseText).jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
    }

    /** Renders a mini-sequencer pattern server-side and stores it as a MUSIC asset. */
    suspend fun createMusicSequence(movieId: String?, sequence: MusicSequence): Asset {
        val body = buildJsonObject {
            if (movieId != null) put("movieId", movieId)
            put("sequence", json.encodeToJsonElement(MusicSequence.serializer(), sequence))
        }.toString()
        val responseText = client.post(url("/api/music/sequence"), body)
        return json.decodeFromString(Asset.serializer(), responseText)
    }

    // ------------------------------------------------------------------- characters and scenes

    suspend fun getCharacters(): List<Character> {
        val responseText = client.get(url("/api/characters"))
        return json.decodeFromString(ListSerializer(Character.serializer()), responseText)
    }

    suspend fun saveCharacter(character: Character, isNew: Boolean): Character {
        val body = json.encodeToString(Character.serializer(), character)
        val responseText = if (isNew) {
            client.post(url("/api/characters"), body)
        } else {
            client.put(url("/api/characters/${character.id}"), body)
        }
        return json.decodeFromString(Character.serializer(), responseText)
    }

    suspend fun deleteCharacter(id: String) {
        client.delete(url("/api/characters/$id"))
    }

    suspend fun getScenes(): List<Scene> {
        val responseText = client.get(url("/api/scenes"))
        return json.decodeFromString(ListSerializer(Scene.serializer()), responseText)
    }

    suspend fun saveScene(scene: Scene, isNew: Boolean): Scene {
        val body = json.encodeToString(Scene.serializer(), scene)
        val responseText = if (isNew) {
            client.post(url("/api/scenes"), body)
        } else {
            client.put(url("/api/scenes/${scene.id}"), body)
        }
        return json.decodeFromString(Scene.serializer(), responseText)
    }

    suspend fun deleteScene(id: String) {
        client.delete(url("/api/scenes/$id"))
    }

    // -------------------------------------------------------------------------------- voices

    suspend fun getVoiceOptions(): VoiceOptions {
        val responseText = client.get(url("/api/voice/options"))
        return json.decodeFromString(VoiceOptions.serializer(), responseText)
    }

    suspend fun createVoiceClone(name: String, audioUrl: String): VoiceClone {
        val body = buildJsonObject {
            put("name", name)
            put("audioUrl", audioUrl)
        }.toString()
        val responseText = client.post(url("/api/voice/clones"), body)
        return json.decodeFromString(VoiceClone.serializer(), responseText)
    }

    suspend fun deleteVoiceClone(id: String) {
        client.delete(url("/api/voice/clones/$id"))
    }

    // ---------------------------------------------------------------------------------- jobs

    suspend fun createJob(job: Job): Job {
        val body = json.encodeToString(Job.serializer(), job)
        val responseText = client.post(url("/api/jobs"), body)
        return json.decodeFromString(Job.serializer(), responseText)
    }

    suspend fun getJob(jobId: String): Job {
        val responseText = client.get(url("/api/jobs/$jobId"))
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Lists jobs for the background-generations panel. */
    suspend fun getJobs(movieId: String?, activeOnly: Boolean): List<Job> {
        val params = mutableListOf<String>()
        if (movieId != null) params.add("movieId=$movieId")
        if (activeOnly) params.add("active=true")
        val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val responseText = client.get(url("/api/jobs$queryString"))
        return json.decodeFromString(ListSerializer(Job.serializer()), responseText)
    }
}

fun generateId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..16).map { chars.random() }.joinToString("")
}
