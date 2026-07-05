package app.moviestudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lifecycle status of a movie. Users can move a movie between these statuses at any time
 * (RENDERING is also set automatically while a final render job is running).
 */
enum class MovieStatus {
    DRAFT,
    IN_PRODUCTION,
    RENDERING,
    REVIEW,
    COMPLETED,
    ARCHIVED,
}

/** Human-readable label for a movie status (user-facing copy always says "Movie"). */
fun MovieStatus.displayName(): String = when (this) {
    MovieStatus.DRAFT -> "Draft"
    MovieStatus.IN_PRODUCTION -> "In production"
    MovieStatus.RENDERING -> "Rendering"
    MovieStatus.REVIEW -> "In review"
    MovieStatus.COMPLETED -> "Completed"
    MovieStatus.ARCHIVED -> "Archived"
}

@Serializable
data class Movie(
    val id: String,
    val title: String,
    // Auto-calculated from the media placed on the timeline; movies have no pre-set length.
    val totalDuration: Double,
    val status: MovieStatus,
    val createdAt: Long,
    // Aspect ratio of the movie (e.g. "16:9"). The player respects this and all media is center-crop fit.
    // Can be changed by the user at any time.
    val aspectRatio: String = DEFAULT_ASPECT_RATIO,
    // Optional cover photo (an image asset's URL) shown on the movie card. Set from the image
    // asset dialog; null means the default placeholder poster is shown.
    val coverImageUrl: String? = null
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
    val zIndex: Int,
    // User-editable track name; when null/blank the UI falls back to the type's default label.
    val name: String? = null
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
    // Failure reason, set when the job transitions to FAILED (shown in the background-
    // generations list so the user can decide to retry or dismiss).
    val error: String? = null,
    val createdAt: Long = 0
)

@Serializable
data class TrackWithClips(
    val track: Track,
    val clips: List<Clip>
)

@Serializable
data class MovieTimeline(
    val movie: Movie,
    val tracks: List<TrackWithClips>,
    // Text-only plot-builder markers pinned to timeline positions. They carry no media but
    // their pinned position still counts towards the movie's length (see [calculatedDuration]).
    val notes: List<TimelineNote> = emptyList()
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
 * Quick-pick voice instruction presets offered by the TTS dialog (Qwen instruct). The user can
 * also type any free-form instruction instead.
 */
val VOICE_INSTRUCTION_PRESETS: List<String> = listOf(
    "Happy", "Sad", "Excited", "Calm", "Angry", "Whispering", "Dramatic"
)

/**
 * A previously saved version of a movie document's content. Auto-save checkpoints the previous
 * content into the document's [MovieDocument.history] so any past version can be restored.
 */
@Serializable
data class DocumentVersion(
    val content: String,
    val savedAt: Long
)

/**
 * A rich-text document attached to a movie: the full script, research, character bios or any
 * other long-form text that is not part of the final movie itself. Documents auto-save, keep a
 * restorable [history], and can nest under a [parentId] to form a tree (children are ordered by
 * [sortIndex]). The content is stored as HTML — the rich editor's interchange format.
 */
@Serializable
data class MovieDocument(
    val id: String,
    val movieId: String,
    val title: String,
    val content: String = "",
    // Parent document id, letting documents nest; null = top level.
    val parentId: String? = null,
    // Position among siblings (lower sorts first).
    val sortIndex: Int = 0,
    // Previous saved versions of the content, most recent first. Restorable.
    val history: List<DocumentVersion> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

/**
 * A text-only note pinned to a position on a movie's timeline — the plot-builder track. Notes
 * carry no media: they are planning/writing aids shown on the timeline as blue markers with
 * their text, and are managed from the editor's expandable notes side panel.
 */
@Serializable
data class TimelineNote(
    val id: String,
    val movieId: String,
    // Timeline position of the note's marker, in seconds.
    val atSeconds: Double,
    val text: String,
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
 * The edge a [TransitionType.SLIDE] transition slides the incoming clip in *from*. The clip starts
 * fully off-screen on that edge and travels to its final centered position over the transition
 * window. [FROM_RIGHT] reproduces the original slide behavior (enter from the right, move left).
 */
enum class SlideDirection {
    FROM_LEFT,
    FROM_RIGHT,
    FROM_TOP,
    FROM_BOTTOM,
}

/** Human-readable label for a slide direction. */
fun SlideDirection.displayName(): String = when (this) {
    SlideDirection.FROM_LEFT -> "From left"
    SlideDirection.FROM_RIGHT -> "From right"
    SlideDirection.FROM_TOP -> "From top"
    SlideDirection.FROM_BOTTOM -> "From bottom"
}

/**
 * Parsed transition settings stored inside a clip's [Clip.effectsConfig] JSON under the
 * `"transition"` key. [durationSeconds] is the length of the transition window measured from the
 * start of the clip. [direction] is only meaningful for [TransitionType.SLIDE].
 */
@Serializable
data class TransitionSpec(
    val type: TransitionType = TransitionType.NONE,
    val durationSeconds: Double = 1.0,
    val direction: SlideDirection = SlideDirection.FROM_RIGHT
)

/** The shortest transition window we render, shared by the preview and the FFmpeg export. */
const val TRANSITION_MIN_SECONDS: Double = 0.05

/**
 * The visual transform applied to the *incoming* clip at a given point in its transition. This is
 * the single definition shared by the live preview and the FFmpeg render so they stay in lock-step.
 *
 * Rather than hard-coding a separate implementation per transition in each renderer (which does not
 * scale to the many transitions we want to offer), a transition is described here as a small set of
 * orthogonal, interpretable *primitives*. Each renderer applies the primitives it can express:
 *
 * - [alpha]: the clip's opacity (0 = fully transparent, 1 = opaque) — a cross-fade.
 * - [translateXFraction] / [translateYFraction]: offset the clip as a fraction of the stage size
 *   (+X = right, +Y = down); a slide starts fully off-screen (|fraction| = 1) and settles at 0.
 * - [revealRadiusFraction]: a centered circular reveal mask, as a fraction of the distance from the
 *   center to a corner (0 = nothing shown, 1 = fully revealed / no mask).
 * - [pixelateFraction]: mosaic amount (0 = crisp, 1 = maximally blocky). Compose modifiers cannot
 *   pixelate arbitrary content, so the live preview leaves this to the FFmpeg render and only
 *   approximates a pixelate transition via its [alpha]; see `docs/Transitions.md`.
 *
 * This primitive set is a bridge, not the endgame: truly arbitrary transitions (the "hundreds" of
 * wipes / irises / dissolves / GL-Transitions) are ultimately a `progress`-driven shader that mixes
 * the from/to frames. See `docs/Transitions.md` for the shader roadmap.
 */
data class TransitionVisual(
    val alpha: Float = 1f,
    val translateXFraction: Float = 0f,
    val translateYFraction: Float = 0f,
    val revealRadiusFraction: Float = 1f,
    val pixelateFraction: Float = 0f
)

/** A clip with no transition: fully opaque, un-offset, fully revealed and crisp. */
val NO_TRANSITION: TransitionVisual = TransitionVisual()

/**
 * Progress of the transition at [clipLocalSeconds] (seconds from the clip's start), clamped to the
 * clip's [clipDuration]. Returns 1f (fully settled, i.e. no effect) when there is no transition or
 * the playhead is past the transition window. Shared by the preview and the FFmpeg export so the
 * window math can't drift between them.
 */
fun TransitionSpec?.progressAt(clipLocalSeconds: Double, clipDuration: Double): Float {
    if (this == null || type == TransitionType.NONE) return 1f
    val window = durationSeconds.coerceIn(TRANSITION_MIN_SECONDS, clipDuration.coerceAtLeast(TRANSITION_MIN_SECONDS))
    if (clipLocalSeconds <= 0.0) return 0f
    if (clipLocalSeconds >= window) return 1f
    return (clipLocalSeconds / window).toFloat().coerceIn(0f, 1f)
}

/**
 * The [TransitionVisual] for this transition at the given [progress] (0..1), expressed with the
 * shared primitives so the preview and the FFmpeg render agree:
 *
 * - [TransitionType.SLIDE]: translate the clip in from its [TransitionSpec.direction] at full
 *   opacity (`translate = ±(1 - progress)`).
 * - [TransitionType.CIRCLE]: a centered circular reveal that grows from nothing to full
 *   (`revealRadiusFraction = progress`) at full opacity — no cross-fade.
 * - [TransitionType.PIXELATE]: the clip resolves out of large mosaic blocks
 *   (`pixelateFraction = 1 - progress`) while it cross-fades in (`alpha = progress`). Compose can't
 *   pixelate the preview, so there it shows as the alpha fade; FFmpeg animates the real mosaic.
 * - [TransitionType.ALPHA] / [NOISE] / [VORONOI]: a cross-fade (`alpha = progress`). The extra
 *   grain FFmpeg layers on top of noise / voronoi is not reproducible with Compose modifiers, so
 *   the preview approximates them as the dominant alpha fade.
 */
fun TransitionSpec.visualAt(progress: Float): TransitionVisual {
    if (type == TransitionType.NONE) return NO_TRANSITION
    val p = progress.coerceIn(0f, 1f)
    return when (type) {
        TransitionType.SLIDE -> {
            val off = 1f - p
            when (direction) {
                SlideDirection.FROM_RIGHT -> TransitionVisual(translateXFraction = off)
                SlideDirection.FROM_LEFT -> TransitionVisual(translateXFraction = -off)
                SlideDirection.FROM_TOP -> TransitionVisual(translateYFraction = -off)
                SlideDirection.FROM_BOTTOM -> TransitionVisual(translateYFraction = off)
            }
        }
        TransitionType.CIRCLE -> TransitionVisual(revealRadiusFraction = p)
        TransitionType.PIXELATE -> TransitionVisual(alpha = p, pixelateFraction = 1f - p)
        else -> TransitionVisual(alpha = p)
    }
}

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
    "Default", "Asap", "Yuyu"
)

/**
 * One keyframe of a clip's volume-over-time envelope (the advanced volume editor).
 * [time] is in seconds from the start of the clip (0 = clip start); [volume] is the gain at that
 * moment (0.0 = silent, 1.0 = 100%, up to [MAX_CLIP_VOLUME]).
 */
@Serializable
data class VolumePoint(
    val time: Double,
    val volume: Double
)

/** The maximum gain a clip's volume (flat or keyframed) can be raised to (200%). */
const val MAX_CLIP_VOLUME: Double = 2.0

private val effectsJson = Json { ignoreUnknownKeys = true }

/**
 * Effects configuration carried by every clip, serialized to JSON in [Clip.effectsConfig].
 * Unknown keys written by other tools are preserved-ignored on parse.
 *
 * [offsetX]/[offsetY] position the media inside the center-crop window (0-100, 50 = centered):
 * 0 shows the left/top edge of the media, 100 the right/bottom edge.
 *
 * [volumeKeyframes] is the optional volume-over-time envelope: when present it overrides the
 * flat [volume], interpolating linearly between keyframes (see [volumeAt]).
 */
@Serializable
data class EffectsConfig(
    val transition: TransitionSpec? = null,
    val captions: CaptionConfig? = null,
    val volume: Double = 1.0,
    val volumeKeyframes: List<VolumePoint> = emptyList(),
    val offsetX: Double = 50.0,
    val offsetY: Double = 50.0
)

/**
 * The clip's gain at [clipSeconds] (seconds from the clip's start). With no keyframes this is the
 * flat [EffectsConfig.volume]; with keyframes the envelope interpolates linearly between them and
 * holds the first/last keyframe's value before/after the envelope.
 */
fun EffectsConfig.volumeAt(clipSeconds: Double): Double {
    if (volumeKeyframes.isEmpty()) return volume
    val points = volumeKeyframes.sortedBy { it.time }
    if (clipSeconds <= points.first().time) return points.first().volume
    if (clipSeconds >= points.last().time) return points.last().volume
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        if (clipSeconds >= a.time && clipSeconds <= b.time) {
            if (b.time <= a.time) return b.volume
            val fraction = (clipSeconds - a.time) / (b.time - a.time)
            return a.volume + (b.volume - a.volume) * fraction
        }
    }
    return points.last().volume
}

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
    val pitch: Int,
    // How many grid steps this note is held for (>= 1). Extended by horizontal dragging.
    val lengthSteps: Int = 1,
    // Per-note instrument: "sine" | "square" | "saw" | "triangle" | "sample", or null to use
    // the sequence-level waveform (kept for patterns saved before per-note instruments).
    val waveform: String? = null,
    // When the note's instrument is a library sound: its media URL (pitch-shifted per row).
    val sampleUrl: String? = null,
    // Human-readable name of the note's sample instrument.
    val sampleName: String? = null
) {
    /** True when this note covers grid [step] (its start plus its held length). */
    fun covers(atStep: Int): Boolean = atStep >= step && atStep < step + lengthSteps.coerceAtLeast(1)
}

/** Tempo bounds accepted by the mini sequencer (UI slider and server synthesizer). */
const val SEQUENCER_MIN_TEMPO_BPM: Int = 20
const val SEQUENCER_MAX_TEMPO_BPM: Int = 240

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
    // "sine" | "square" | "saw" | "triangle", or "sample" when a library sound is the instrument.
    val waveform: String = "sine",
    // When the instrument is a library sound effect: its media URL (pitch-shifted per row).
    val sampleUrl: String? = null,
    // Human-readable name of the sample instrument (shown in the instrument picker).
    val sampleName: String? = null,
    // The key the grid highlights: "major" | "minor". Off-scale rows can still hold notes when
    // the user reveals all pitches, but are drawn in an alternate colour.
    val scale: String = "major",
    val notes: List<SequencerNote> = emptyList()
) {
    /** Number of whole measures in the pattern (16 steps each). */
    val measures: Int get() = (steps / SEQUENCER_STEPS_PER_MEASURE).coerceAtLeast(1)
}

/** Grid layout constants shared by the client dialog and the server synthesizer. */
const val SEQUENCER_STEPS_PER_MEASURE: Int = 16

/** How many octaves the sequencer grid shows at once (octave arrows shift the window). */
const val SEQUENCER_VISIBLE_OCTAVES: Int = 2

/** The lowest sounding pitch (semitone 0): C2. Every pitch is a semitone offset above it. */
const val SEQUENCER_BASE_FREQUENCY: Double = 65.40639132514966

/** Highest pitch (semitone offset) the grid allows: C7, five octaves above the C2 root. */
const val SEQUENCER_MAX_PITCH: Int = 60

/** Semitone intervals (within an octave) that belong to a major key. */
val SEQUENCER_MAJOR_INTERVALS: List<Int> = listOf(0, 2, 4, 5, 7, 9, 11)

/** Semitone intervals (within an octave) that belong to a natural-minor key. */
val SEQUENCER_MINOR_INTERVALS: List<Int> = listOf(0, 2, 3, 5, 7, 8, 10)

/** The in-key semitone intervals for the named [scale] ("minor" -> natural minor, else major). */
fun sequencerScaleIntervals(scale: String): List<Int> =
    if (scale.equals("minor", ignoreCase = true)) SEQUENCER_MINOR_INTERVALS else SEQUENCER_MAJOR_INTERVALS

/** True when the given absolute [pitch] (semitones above the root) is part of [scale]. */
fun isPitchInScale(pitch: Int, scale: String): Boolean =
    (((pitch % 12) + 12) % 12) in sequencerScaleIntervals(scale)

/**
 * Frequency (Hz) for a sequencer note. [pitch] is an absolute chromatic semitone offset above the
 * C2 root, so a difference of 12 is exactly one octave. Shared by the server synthesizer and the
 * in-dialog playback so both play the exact same notes.
 */
fun sequencerRowFrequency(pitch: Int): Double {
    val semitone = pitch.coerceIn(0, SEQUENCER_MAX_PITCH)
    var factor = 1.0
    repeat(semitone) { factor *= 1.0594630943592953 } // 2^(1/12) without needing math libs
    return SEQUENCER_BASE_FREQUENCY * factor
}

// ---------------------------------------------------------------------------------------------
// Media generation setups (section: generating images and videos)
// ---------------------------------------------------------------------------------------------

/**
 * A complete, reusable description of an AI media generation. Stored on the generated asset
 * (see [Asset.generationConfig]) so generations can be retried or tweaked and re-run.
 *
 * The model is selected predictably from the attached inputs:
 * - video + a base video -> video edit (wan2.7-videoedit)
 * - video + reference images / characters / scenes -> R2V
 * - video + a start image -> I2V
 * - video + prompt only -> T2V
 * - image -> text-to-image (or image edit when a base image is attached)
 */
@Serializable
data class GenerationSetup(
    val kind: String, // "video" | "image" | "music" | "tts" | "sfx"
    val prompt: String = "",
    val negativePrompt: String = "",
    val imageUrl: String? = null,
    // A base video to edit (switches video generation to the wan2.7-videoedit model).
    val videoUrl: String? = null,
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
    val voice: String = "",
    // Optional voice instructions for TTS (Qwen instruct): how the line should be delivered,
    // e.g. "happy", "sad", "excited", "whispering, slightly out of breath".
    val instructions: String = "",
    // Sound-effect-specific options: which generation mode produces the audio (see
    // [SUPPORTED_SFX_MODELS]).
    val sfxModel: String = "wan"
) {
    /** The WAN model family this setup resolves to (for video/image kinds). */
    fun resolveVideoModelKind(): String = when {
        kind != "video" -> kind
        videoUrl != null -> "videoedit"
        referenceImages.isNotEmpty() || characterIds.isNotEmpty() || sceneIds.isNotEmpty() -> "r2v"
        imageUrl != null -> "i2v"
        else -> "t2v"
    }
}

// ---------------------------------------------------------------------------------------------
// AI text chat (section: AI prompt dialogs — editable prompts with follow-up refinement)
// ---------------------------------------------------------------------------------------------

/** Role of an [AiChatMessage] author: the user asking, or the AI assistant answering. */
object AiChatRole {
    const val USER = "user"
    const val ASSISTANT = "assistant"
}

/**
 * One turn of an AI text conversation, exchanged between the app's AI prompt dialogs and the
 * server's text-generation endpoints. The history is kept client-side and sent in full with
 * every request, so follow-up messages can refine the previous AI response.
 */
@Serializable
data class AiChatMessage(
    val role: String, // see [AiChatRole]
    val content: String
)

// ---------------------------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------------------------

/**
 * The set of aspect ratios a user can pick for a movie. All media is center-crop fit to
 * whichever ratio is currently selected.
 */
val SUPPORTED_ASPECT_RATIOS: List<String> = listOf("16:9", "9:16", "1:1", "4:3", "21:9")

/**
 * Video generation sizes supported by the WAN 2.7 family (480p / 720p / 1080p tiers, landscape,
 * portrait and square variants).
 */
val SUPPORTED_VIDEO_SIZES: List<String> = listOf(
    "1280*720", "720*1280", "960*960",
    "1920*1080", "1080*1920", "1440*1440",
    "832*480", "480*832", "624*624"
)

/** Image generation sizes offered by the text-to-image / image-edit models. */
val SUPPORTED_IMAGE_SIZES: List<String> = listOf(
    "1024*1024", "1280*720", "720*1280", "768*1024", "1024*768"
)

/**
 * Sound-effect generation modes a user can pick (see [GenerationSetup.sfxModel]); both
 * "fun-audiogen*" modes are served by the same DashScope audio-generation model:
 * - "wan": a short WAN video is generated and its audio track extracted (legacy pipeline).
 * - "fun-audiogen": synthesizes the audio directly from the text prompt.
 * - "fun-audiogen-vd": video-driven, scores a WAN source video with audio matching its visuals.
 */
val SUPPORTED_SFX_MODELS: List<String> = listOf("wan", "fun-audiogen", "fun-audiogen-vd")

/**
 * Picks the generation size (e.g. "1280*720") from [sizes] whose aspect is closest to the
 * movie's [aspectRatio] (e.g. "16:9"), so generated media crops as little as possible.
 */
fun closestSizeForAspect(sizes: List<String>, aspectRatio: String): String {
    val target = aspectRatioToFloat(aspectRatio)
    return sizes.minByOrNull { size ->
        val parts = size.split('*', 'x')
        val w = parts.getOrNull(0)?.trim()?.toFloatOrNull()
        val h = parts.getOrNull(1)?.trim()?.toFloatOrNull()
        if (w != null && h != null && w > 0f && h > 0f) {
            val ratio = w / h
            if (ratio > target) ratio / target else target / ratio
        } else {
            Float.MAX_VALUE
        }
    } ?: sizes.firstOrNull() ?: "1280*720"
}

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
 * Auto-calculates a movie's length from its timeline. Movies have no pre-set length: the
 * duration is the furthest point anything reaches on the timeline — the furthest point any clip
 * reaches or, when a note is pinned past the last clip, the furthest note marker. Notes have no
 * length of their own, so they contribute only their pinned position.
 */
fun MovieTimeline.calculatedDuration(): Double {
    val clipEnd = tracks
        .flatMap { it.clips }
        .maxOfOrNull { (it.timelineStart + (it.trimOut - it.trimIn)).toDouble() }
        ?: 0.0
    val noteEnd = notes.maxOfOrNull { it.atSeconds } ?: 0.0
    val end = maxOf(clipEnd, noteEnd)
    return if (end < 0.0) 0.0 else end
}
