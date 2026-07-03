package app.moviestudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lifecycle status of a movie. Users can move a movie between these statuses at any time
 * (RENDERING is also set automatically while a final render job is running).
 */
enum class FilmStatus {
    DRAFT,
    IN_PRODUCTION,
    RENDERING,
    REVIEW,
    COMPLETED,
    ARCHIVED,
}

/** Human-readable label for a movie status (user-facing copy always says "Movie"). */
fun FilmStatus.displayName(): String = when (this) {
    FilmStatus.DRAFT -> "Draft"
    FilmStatus.IN_PRODUCTION -> "In production"
    FilmStatus.RENDERING -> "Rendering"
    FilmStatus.REVIEW -> "In review"
    FilmStatus.COMPLETED -> "Completed"
    FilmStatus.ARCHIVED -> "Archived"
}

@Serializable
data class Film(
    val id: String,
    val title: String,
    // Auto-calculated from the media placed on the timeline; movies have no pre-set length.
    val totalDuration: Double,
    val status: FilmStatus,
    val createdAt: Long,
    // Aspect ratio of the movie (e.g. "16:9"). The player respects this and all media is center-crop fit.
    // Can be changed by the user at any time.
    val aspectRatio: String = DEFAULT_ASPECT_RATIO
) {
    companion object {
        const val DEFAULT_ASPECT_RATIO: String = "16:9"
    }
}

/**
 * A single spoken word within a voice/voiceover [Asset]'s transcript together with its
 * start/end timing (in seconds) relative to the asset's own timeline.
 */
@Serializable
data class WordTiming(
    val word: String,
    val start: Double,
    val end: Double
)

enum class AssetType {
    VIDEO,
    AUDIO,
    MUSIC,
    VOICE,
    IMAGE,
    TEXT,
}

/**
 * A previously generated version of an asset's media. Every regeneration pushes the old media
 * onto the asset's [Asset.history] so the user can restore any prior version at any time.
 */
@Serializable
data class AssetVersion(
    val ossUrl: String,
    val durationSeconds: Double,
    val createdAt: Long,
    val prompt: String? = null
)

@Serializable
data class Asset(
    val id: String,
    val type: AssetType,
    val ossUrl: String,
    val durationSeconds: Double,
    val movieId: String?,
    val tags: List<String>,
    val aiPrompt: String?,
    // The user-provided textual description for this media. Preserved for every media type and
    // however the asset was created — added by description, uploaded from the device, or generated.
    val description: String? = null,
    // For voice media types (voiceover), the auto-generated (and user-editable) transcript text.
    val transcript: String? = null,
    // Per-word timings backing the transcript, used by the transcript editor/viewer.
    val wordTimings: List<WordTiming> = emptyList(),
    // Playback offset (seconds) into the source media file. Used by clipped sound effects that
    // reference a window of a larger file: playback/render starts at this offset.
    val sourceOffsetSeconds: Double = 0.0,
    // JSON snapshot of the generation setup (model, prompts, references, options) used to create
    // this media, so the generation can be retried or tweaked and re-run at any time.
    val generationConfig: String? = null,
    // Previous generated versions of this asset's media, most recent first. Restorable.
    val history: List<AssetVersion> = emptyList(),
    // For VOICE assets: the preset or cloned voice used for TTS.
    val voice: String? = null,
    val createdAt: Long = 0
) {
    /** True when this asset has no media yet — it exists as a textual description only. */
    val isDescriptionOnly: Boolean get() = ossUrl.isBlank()
}

enum class TrackType {
    VIDEO,
    MUSIC,
    VOICE,
    EFFECTS,
}

@Serializable
data class Track(
    val id: String,
    val movieId: String,
    val type: TrackType,
    val zIndex: Int
)

@Serializable
data class Clip(
    val id: String,
    val trackId: String,
    val assetId: String,
    val timelineStart: Float,
    val trimIn: Float,
    val trimOut: Float,
    val effectsConfig: String
)

enum class JobType {
    AI_GEN, FFMPEG_RENDER, SKELETON
}

enum class JobStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}

@Serializable
data class Job(
    val id: String,
    val movieId: String,
    val type: JobType,
    val status: JobStatus,
    val payload: String,
    val resultUrl: String?,
    // Short human-readable label describing what is being generated (shown in the
    // background-generations list).
    val label: String? = null,
    val createdAt: Long = 0
)

@Serializable
data class TrackWithClips(
    val track: Track,
    val clips: List<Clip>
)

@Serializable
data class FilmTimeline(
    val movie: Film,
    val tracks: List<TrackWithClips>
)

@Serializable
data class JobProgressEvent(
    val jobId: String,
    val movieId: String,
    val status: JobStatus,
    val progress: Int,
    val message: String? = null,
    val resultUrl: String? = null,
    // The job type, letting clients react differently (e.g. reload the timeline once a
    // SKELETON job completes).
    val jobType: JobType? = null
)

/**
 * A saved character the user can reference in AI generations: a name, a text description and
 * up to [MAX_REFERENCE_IMAGES] reference images.
 */
@Serializable
data class Character(
    val id: String,
    val name: String,
    val description: String,
    val referenceImages: List<String> = emptyList(),
    val createdAt: Long = 0
) {
    companion object {
        const val MAX_REFERENCE_IMAGES = 3
    }
}

/**
 * A saved scene (location/setting) the user can reference in AI generations: a name, a text
 * description and up to [MAX_REFERENCE_IMAGES] reference images.
 */
@Serializable
data class Scene(
    val id: String,
    val name: String,
    val description: String,
    val referenceImages: List<String> = emptyList(),
    val createdAt: Long = 0
) {
    companion object {
        const val MAX_REFERENCE_IMAGES = 3
    }
}

/**
 * A user-created cloned voice (Qwen voice cloning, China mainland). The [qwenVoiceId] is the id
 * returned by the voice-enrollment API and is what TTS requests reference.
 */
@Serializable
data class VoiceClone(
    val id: String,
    val name: String,
    val qwenVoiceId: String,
    val sourceAudioUrl: String,
    val createdAt: Long = 0
)

/**
 * A completed render of a movie. Every render is kept so the user can replay or download any
 * past render at any time.
 */
@Serializable
data class RenderRecord(
    val id: String,
    val movieId: String,
    val url: String,
    val durationSeconds: Double,
    val aspectRatio: String,
    val createdAt: Long = 0
)

/**
 * The Qwen TTS preset voices available out of the box. Cloned voices from the user's voice
 * library are offered alongside these.
 */
val QWEN_VOICE_PRESETS: List<String> = listOf(
    "Cherry", "Serena", "Ethan", "Chelsie", "Dylan", "Jada", "Sunny"
)

/** All selectable voices: Qwen presets plus the user's cloned voices. */
@Serializable
data class VoiceOptions(
    val presets: List<String> = emptyList(),
    val clones: List<VoiceClone> = emptyList()
)

// ---------------------------------------------------------------------------------------------
// Transitions (clip overlap effects)
// ---------------------------------------------------------------------------------------------

/**
 * The transition applied while a clip overlaps the media playing underneath it. The transition
 * progresses from 0% to 100% across the transition window at the start of the clip.
 */
enum class TransitionType {
    NONE,
    ALPHA,
    NOISE,
    VORONOI,
    SLIDE,
    CIRCLE,
    PIXELATE,
}

/** Human-readable label for a transition type. */
fun TransitionType.displayName(): String = when (this) {
    TransitionType.NONE -> "None"
    TransitionType.ALPHA -> "Alpha fade"
    TransitionType.NOISE -> "Noise dissolve"
    TransitionType.VORONOI -> "Voronoi cells"
    TransitionType.SLIDE -> "Slide"
    TransitionType.CIRCLE -> "Circle reveal"
    TransitionType.PIXELATE -> "Pixelate"
}

/**
 * Parsed transition settings stored inside a clip's [Clip.effectsConfig] JSON under the
 * `"transition"` key. [durationSeconds] is the length of the transition window measured from the
 * start of the clip.
 */
@Serializable
data class TransitionSpec(
    val type: TransitionType = TransitionType.NONE,
    val durationSeconds: Double = 1.0
)

/**
 * Caption rendering settings for a voice clip, stored inside [Clip.effectsConfig] under the
 * `"captions"` key.
 */
@Serializable
data class CaptionConfig(
    val enabled: Boolean = false,
    val fontFamily: String = "Default",
    val fontSizeSp: Int = 28,
    val color: String = "#FFFFFF",
    // Vertical placement: "bottom", "center" or "top".
    val position: String = "bottom"
)

/** Font families offered by the caption font chooser ("My fonts" are the app-bundled ones). */
val CAPTION_FONT_FAMILIES: List<String> = listOf(
    "Default", "Serif", "Sans serif", "Monospace", "Cursive", "Asap (my font)", "Yuyu (my font)"
)

private val effectsJson = Json { ignoreUnknownKeys = true }

/**
 * Effects configuration carried by every clip, serialized to JSON in [Clip.effectsConfig].
 * Unknown keys written by other tools are preserved-ignored on parse.
 */
@Serializable
data class EffectsConfig(
    val transition: TransitionSpec? = null,
    val captions: CaptionConfig? = null,
    val volume: Double = 1.0
)

/** Parses a clip's [Clip.effectsConfig] JSON. Malformed/empty input yields default settings. */
fun parseEffectsConfig(raw: String?): EffectsConfig {
    if (raw.isNullOrBlank()) return EffectsConfig()
    return try {
        effectsJson.decodeFromString(EffectsConfig.serializer(), raw)
    } catch (e: Exception) {
        EffectsConfig()
    }
}

/** Serializes [config] back into the JSON string stored on a clip. */
fun encodeEffectsConfig(config: EffectsConfig): String =
    effectsJson.encodeToString(EffectsConfig.serializer(), config)

// ---------------------------------------------------------------------------------------------
// Mini music sequencer
// ---------------------------------------------------------------------------------------------

/** A single note placed on the mini music sequencer grid. */
@Serializable
data class SequencerNote(
    // Step index on the grid (16 steps per pattern by default).
    val step: Int,
    // Semitone offset within the pattern's scale (row index, bottom = 0).
    val pitch: Int
)

/**
 * A pattern created in the mini music sequencer. Synthesized server-side to a WAV and stored as
 * a MUSIC asset; the pattern itself is preserved in the asset's [Asset.generationConfig] so the
 * user can reopen and edit it later.
 */
@Serializable
data class MusicSequence(
    val name: String = "Sequence",
    val tempoBpm: Int = 120,
    val steps: Int = 16,
    // Number of times the pattern repeats in the rendered file.
    val loops: Int = 4,
    val waveform: String = "sine", // sine | square | saw | triangle
    val notes: List<SequencerNote> = emptyList()
)

// ---------------------------------------------------------------------------------------------
// Media generation setups (section: generating images and videos)
// ---------------------------------------------------------------------------------------------

/**
 * A complete, reusable description of an AI media generation. Stored on the generated asset
 * (see [Asset.generationConfig]) so generations can be retried or tweaked and re-run.
 *
 * The model is selected predictably from the attached inputs:
 * - video + reference images / characters / scenes -> R2V
 * - video + a start image -> I2V
 * - video + prompt only -> T2V
 * - image -> text-to-image
 */
@Serializable
data class GenerationSetup(
    val kind: String, // "video" | "image" | "music" | "tts" | "sfx"
    val prompt: String = "",
    val negativePrompt: String = "",
    val imageUrl: String? = null,
    val referenceImages: List<String> = emptyList(),
    val characterIds: List<String> = emptyList(),
    val sceneIds: List<String> = emptyList(),
    val durationSeconds: Double = 5.0,
    val resolution: String = "1280*720",
    // Music-specific options.
    val lyric: String = "",
    val theme: String = "",
    val instrumental: Boolean = false,
    // Voice-specific options.
    val voice: String = ""
) {
    /** The WAN model family this setup resolves to (for video/image kinds). */
    fun resolveVideoModelKind(): String = when {
        kind != "video" -> kind
        referenceImages.isNotEmpty() || characterIds.isNotEmpty() || sceneIds.isNotEmpty() -> "r2v"
        imageUrl != null -> "i2v"
        else -> "t2v"
    }
}

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

/**
 * The set of aspect ratios a user can pick for a movie. All media is center-crop fit to
 * whichever ratio is currently selected.
 */
val SUPPORTED_ASPECT_RATIOS: List<String> = listOf("16:9", "9:16", "1:1", "4:3", "21:9")

/**
 * Parses an aspect ratio string like "16:9" into its numeric width/height factor.
 * Falls back to 16:9 for malformed input so the UI never divides by zero.
 */
fun aspectRatioToFloat(ratio: String): Float {
    val parts = ratio.split(":", "/", "x")
    val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()
    val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()
    return if (w != null && h != null && w > 0f && h > 0f) w / h else 16f / 9f
}

/**
 * Splits transcript [text] into words and evenly distributes [WordTiming]s across
 * [durationSeconds]. Used when a transcript is edited by hand so word timings stay in sync.
 */
fun buildWordTimings(text: String, durationSeconds: Double): List<WordTiming> {
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return emptyList()
    val duration = if (durationSeconds > 0.0) durationSeconds else words.size.toDouble()
    val per = duration / words.size
    return words.mapIndexed { index, word ->
        WordTiming(word = word, start = index * per, end = (index + 1) * per)
    }
}

/**
 * Auto-calculates a movie's length from the media on its timeline. Movies have no pre-set
 * length: the duration is simply the furthest point any clip reaches on the timeline.
 */
fun FilmTimeline.calculatedDuration(): Double {
    val end = tracks
        .flatMap { it.clips }
        .maxOfOrNull { (it.timelineStart + (it.trimOut - it.trimIn)).toDouble() }
        ?: 0.0
    return if (end < 0.0) 0.0 else end
}
