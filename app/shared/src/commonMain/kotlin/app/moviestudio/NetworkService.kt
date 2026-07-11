package app.moviestudio

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Response returned by the server when requesting a pre-signed upload URL for an object key. */
data class UploadUrlResponse(
    val uploadUrl: String,
    val objectKey: String,
    /** Long-lived signed GET URL to persist as the asset's read URL (objects are private on OSS). */
    val downloadUrl: String,
    /** The exact Content-Type the PUT must send; it is part of the upload URL's signature. */
    val contentType: String
)

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

    /** Percent-encodes a value so it can be safely used as a URL query-string component. */
    private fun encodeQueryParam(value: String): String {
        val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
        val sb = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            if (ch in unreserved) {
                sb.append(ch)
            } else {
                sb.append('%')
                sb.append(((code shr 4) and 0xF).toString(16).uppercase())
                sb.append((code and 0xF).toString(16).uppercase())
            }
        }
        return sb.toString()
    }

    /** The base `ws(s)://` origin (the HTTP base URL with its scheme swapped for WebSockets). */
    private fun wsBaseUrl(): String = getBaseUrl().removeSuffix("/")
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    /** The WebSocket URL streaming job progress events for the given movie. */
    fun jobEventsWsUrl(movieId: String): String = "${wsBaseUrl()}/api/jobs/ws?movieId=$movieId"

    /**
     * The WebSocket URL for realtime dictation: the hold-to-dictate fallback streams microphone
     * PCM here and receives a running transcript back when the browser lacks the Web Speech API.
     */
    fun speechWsUrl(): String = "${wsBaseUrl()}/api/speech/ws"

    // ------------------------------------------------------------------------------- movies

    suspend fun getMovies(): List<Movie> {
        val responseText = client.get(url("/api/movies"))
        return json.decodeFromString(ListSerializer(Movie.serializer()), responseText)
    }

    suspend fun createMovie(movie: Movie): Movie {
        val body = json.encodeToString(Movie.serializer(), movie)
        val responseText = client.post(url("/api/movies"), body)
        return json.decodeFromString(Movie.serializer(), responseText)
    }

    suspend fun updateMovie(movie: Movie): Movie {
        val body = json.encodeToString(Movie.serializer(), movie)
        val responseText = client.put(url("/api/movies/${movie.id}"), body)
        return json.decodeFromString(Movie.serializer(), responseText)
    }

    suspend fun deleteMovie(id: String) {
        client.delete(url("/api/movies/$id"))
    }

    suspend fun getTimeline(movieId: String): MovieTimeline {
        val responseText = client.get(url("/api/movies/$movieId/timeline"))
        return json.decodeFromString(MovieTimeline.serializer(), responseText)
    }

    suspend fun createTrack(movieId: String, track: Track): Track {
        val body = json.encodeToString(Track.serializer(), track)
        val responseText = client.post(url("/api/movies/$movieId/tracks"), body)
        return json.decodeFromString(Track.serializer(), responseText)
    }

    suspend fun updateTrack(movieId: String, track: Track): Track {
        val body = json.encodeToString(Track.serializer(), track)
        val responseText = client.put(url("/api/movies/$movieId/tracks/${track.id}"), body)
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

    /** All timeline notes of the movie (text-only plot-builder markers), sorted by time. */
    suspend fun getNotes(movieId: String): List<TimelineNote> {
        val responseText = client.get(url("/api/movies/$movieId/notes"))
        return json.decodeFromString(ListSerializer(TimelineNote.serializer()), responseText)
    }

    suspend fun createNote(movieId: String, note: TimelineNote): TimelineNote {
        val body = json.encodeToString(TimelineNote.serializer(), note)
        val responseText = client.post(url("/api/movies/$movieId/notes"), body)
        return json.decodeFromString(TimelineNote.serializer(), responseText)
    }

    suspend fun updateNote(movieId: String, note: TimelineNote): TimelineNote {
        val body = json.encodeToString(TimelineNote.serializer(), note)
        val responseText = client.put(url("/api/movies/$movieId/notes/${note.id}"), body)
        return json.decodeFromString(TimelineNote.serializer(), responseText)
    }

    suspend fun deleteNote(movieId: String, noteId: String) {
        client.delete(url("/api/movies/$movieId/notes/$noteId"))
    }

    /** All rich-text documents of the movie (script, research...), sorted by tree position. */
    suspend fun getDocuments(movieId: String): List<MovieDocument> {
        val responseText = client.get(url("/api/movies/$movieId/documents"))
        return json.decodeFromString(ListSerializer(MovieDocument.serializer()), responseText)
    }

    suspend fun createDocument(movieId: String, document: MovieDocument): MovieDocument {
        val body = json.encodeToString(MovieDocument.serializer(), document)
        val responseText = client.post(url("/api/movies/$movieId/documents"), body)
        return json.decodeFromString(MovieDocument.serializer(), responseText)
    }

    suspend fun updateDocument(movieId: String, document: MovieDocument): MovieDocument {
        val body = json.encodeToString(MovieDocument.serializer(), document)
        val responseText = client.put(url("/api/movies/$movieId/documents/${document.id}"), body)
        return json.decodeFromString(MovieDocument.serializer(), responseText)
    }

    suspend fun deleteDocument(movieId: String, documentId: String) {
        client.delete(url("/api/movies/$movieId/documents/$documentId"))
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

    // --------------------------------------------------------------------------------- tips

    /** Studio-wide tips, newest first. When [query] is non-blank, only matching tips are returned. */
    suspend fun getTips(query: String = ""): List<Tip> {
        val queryString = if (query.isNotBlank()) "?q=${encodeQueryParam(query)}" else ""
        val responseText = client.get(url("/api/tips$queryString"))
        return json.decodeFromString(ListSerializer(Tip.serializer()), responseText)
    }

    suspend fun createTip(tip: Tip): Tip {
        val body = json.encodeToString(Tip.serializer(), tip)
        val responseText = client.post(url("/api/tips"), body)
        return json.decodeFromString(Tip.serializer(), responseText)
    }

    suspend fun updateTip(tip: Tip): Tip {
        val body = json.encodeToString(Tip.serializer(), tip)
        val responseText = client.put(url("/api/tips/${tip.id}"), body)
        return json.decodeFromString(Tip.serializer(), responseText)
    }

    suspend fun deleteTip(id: String) {
        client.delete(url("/api/tips/$id"))
    }

    // ------------------------------------------------------------------------------- issues

    /** Reported issues, open first then newest. When [query] is non-blank, only matching issues are returned. */
    suspend fun getIssues(query: String = ""): List<Issue> {
        val queryString = if (query.isNotBlank()) "?q=${encodeQueryParam(query)}" else ""
        val responseText = client.get(url("/api/issues$queryString"))
        return json.decodeFromString(ListSerializer(Issue.serializer()), responseText)
    }

    suspend fun createIssue(issue: Issue): Issue {
        val body = json.encodeToString(Issue.serializer(), issue)
        val responseText = client.post(url("/api/issues"), body)
        return json.decodeFromString(Issue.serializer(), responseText)
    }

    suspend fun updateIssue(issue: Issue): Issue {
        val body = json.encodeToString(Issue.serializer(), issue)
        val responseText = client.put(url("/api/issues/${issue.id}"), body)
        return json.decodeFromString(Issue.serializer(), responseText)
    }

    suspend fun deleteIssue(id: String) {
        client.delete(url("/api/issues/$id"))
    }

    // ------------------------------------------------------------------------------- fonts

    /** Searches the Google Fonts catalog; blank filters are omitted (match everything). */
    suspend fun searchFonts(query: String = "", subset: String = "", category: String = ""): FontSearchResponse {
        val params = buildList {
            if (query.isNotBlank()) add("q=${encodeQueryParam(query)}")
            if (subset.isNotBlank()) add("subset=${encodeQueryParam(subset)}")
            if (category.isNotBlank()) add("category=${encodeQueryParam(category)}")
        }
        val suffix = if (params.isEmpty()) "" else "?${params.joinToString("&")}"
        val responseText = client.get(url("/api/fonts$suffix"))
        return json.decodeFromString(FontSearchResponse.serializer(), responseText)
    }

    /** The user's pinned and recently used font families (studio-wide, across all movies). */
    suspend fun getFontPrefs(): FontPrefsResponse {
        val responseText = client.get(url("/api/fonts/prefs"))
        return json.decodeFromString(FontPrefsResponse.serializer(), responseText)
    }

    /** Pins or unpins a font family in the picker; returns the updated pinned/recent lists. */
    suspend fun pinFont(family: String, pinned: Boolean): FontPrefsResponse {
        val body = buildJsonObject {
            put("family", family)
            put("pinned", pinned)
        }.toString()
        val responseText = client.post(url("/api/fonts/pin"), body)
        return json.decodeFromString(FontPrefsResponse.serializer(), responseText)
    }

    /**
     * Resolves a font family+variant to its durable OSS-hosted file. The server downloads the
     * `.ttf` from Google only the first time the variant is ever requested, then reuses it.
     */
    suspend fun ensureFont(family: String, variant: String): StudioFont {
        val body = buildJsonObject {
            put("family", family)
            put("variant", variant)
        }.toString()
        val responseText = client.post(url("/api/fonts/ensure"), body)
        return json.decodeFromString(StudioFont.serializer(), responseText)
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
        val uploadUrl = obj["uploadUrl"]?.jsonPrimitive?.content.orEmpty()
        return UploadUrlResponse(
            uploadUrl = uploadUrl,
            objectKey = obj["objectKey"]?.jsonPrimitive?.content.orEmpty(),
            downloadUrl = obj["downloadUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: uploadUrl.substringBefore('?'),
            contentType = obj["contentType"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "application/octet-stream"
        )
    }

    suspend fun generateTranscript(assetId: String): Asset {
        val responseText = client.post(url("/api/assets/$assetId/transcript"))
        return json.decodeFromString(Asset.serializer(), responseText)
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
    suspend fun generateMedia(
        movieId: String?,
        setup: GenerationSetup,
        assetId: String? = null,
        sourceAssetId: String? = null
    ): Job {
        val body = buildJsonObject {
            if (movieId != null) put("movieId", movieId)
            put("setup", json.encodeToJsonElement(GenerationSetup.serializer(), setup))
            if (assetId != null) put("assetId", assetId)
            if (sourceAssetId != null) put("sourceAssetId", sourceAssetId)
        }.toString()
        val responseText = client.post(url("/api/generate/media"), body)
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /**
     * Generates song lyrics from a conversation (single prompt or follow-up refinements —
     * newest message last). Returns the AI's raw lyrics text.
     */
    suspend fun generateLyrics(messages: List<AiChatMessage>, movieTitle: String): String =
        generateChatText("/api/generate/lyrics", messages, movieTitle)

    /**
     * Suggests a musical theme description from a conversation (single prompt or follow-up
     * refinements — newest message last). Returns the AI's raw theme text.
     */
    suspend fun generateTheme(messages: List<AiChatMessage>, movieTitle: String): String =
        generateChatText("/api/generate/theme", messages, movieTitle)

    /**
     * Generic conversational text generation backing the app-wide AI chat (Alt+Enter in any
     * [app.moviestudio.ui.StudioTextField]). Returns the AI's raw text so it can be inserted into
     * the field.
     */
    suspend fun generateText(messages: List<AiChatMessage>, movieTitle: String = ""): String =
        generateChatText("/api/generate/text", messages, movieTitle)

    /** Shared POST for the conversational text-generation endpoints (lyrics, theme, ...). */
    private suspend fun generateChatText(path: String, messages: List<AiChatMessage>, movieTitle: String): String {
        val body = buildJsonObject {
            put("movieTitle", movieTitle)
            put("messages", json.encodeToJsonElement(ListSerializer(AiChatMessage.serializer()), messages))
        }.toString()
        val responseText = client.post(url(path), body)
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

    /** Designs a new voice (CosyVoice Voice Design) from a natural-language description. */
    suspend fun createVoiceDesign(name: String, description: String): VoiceDesign {
        val body = buildJsonObject {
            put("name", name)
            put("description", description)
        }.toString()
        val responseText = client.post(url("/api/voice/designs"), body)
        return json.decodeFromString(VoiceDesign.serializer(), responseText)
    }

    suspend fun deleteVoiceDesign(id: String) {
        client.delete(url("/api/voice/designs/$id"))
    }

    /**
     * Requests a short spoken preview of [voiceId] and returns a playable audio URL (empty when
     * the backend has no preview, e.g. AI is not configured).
     */
    suspend fun sampleVoice(voiceId: String, text: String = ""): String {
        val body = buildJsonObject {
            put("voiceId", voiceId)
            put("text", text)
        }.toString()
        val responseText = client.post(url("/api/voice/sample"), body)
        return json.parseToJsonElement(responseText).jsonObject["url"]?.jsonPrimitive?.content.orEmpty()
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

    /** Lists jobs for the background-generations panel ([includeFailed] keeps FAILED jobs in). */
    suspend fun getJobs(movieId: String?, activeOnly: Boolean, includeFailed: Boolean = false): List<Job> {
        val params = mutableListOf<String>()
        if (movieId != null) params.add("movieId=$movieId")
        if (activeOnly) params.add("active=true")
        if (includeFailed) params.add("includeFailed=true")
        val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
        val responseText = client.get(url("/api/jobs$queryString"))
        return json.decodeFromString(ListSerializer(Job.serializer()), responseText)
    }

    /** Re-queues a failed job (back to PENDING; the server worker picks it up again). */
    suspend fun retryJob(jobId: String): Job {
        val responseText = client.post(url("/api/jobs/$jobId/retry"))
        return json.decodeFromString(Job.serializer(), responseText)
    }

    /** Dismisses (deletes) a job — used to clear failed generations from the panel. */
    suspend fun dismissJob(jobId: String) {
        client.delete(url("/api/jobs/$jobId"))
    }
}

fun generateId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    return (1..16).map { chars.random() }.joinToString("")
}
