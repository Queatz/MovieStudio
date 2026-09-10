package app.moviestudio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

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
    val coverImageUrl: String? = null,
    // Optional free-form, multi-line description of the movie. Shown (and editable) in the New
    // Movie dialog, under the title on the dashboard card and in the editor top bar. Blank means
    // no description has been set.
    val description: String = "",
    // Last resolution ("W*H") used to generate an image for this movie, remembered so the
    // generate-image dialog pre-selects it next time. Null until an image has been generated.
    val lastImageResolution: String? = null,
    // Last image-generation model id (one of [SUPPORTED_IMAGE_MODELS]) used for this movie,
    // remembered alongside [lastImageResolution]. Null until an image has been generated.
    val lastImageModel: String? = null,
    // Last resolution ("W*H") used to generate a video for this movie, remembered so the
    // generate-video dialog pre-selects it next time. Null until a video has been generated.
    val lastVideoResolution: String? = null,
    // Last TTS voice id used for this movie (a Qwen preset id, or a cloned/designed voice's
    // qwenVoiceId), remembered so the text-to-speech dialog pre-selects it next time. Null until
    // a voiceover has been generated for this movie — new movies still fall back to Cherry.
    val lastVoice: String? = null,
    // Last visual style id ([VisualStyle.id]) used when generating an image/video for this movie,
    // remembered so the generate-media dialog pre-selects it next time. Null until a visual style
    // has been applied for this movie (or the user explicitly chose "None").
    val lastStyleId: String? = null,
    // Last horizontal scroll offset (in seconds) of this movie's timeline, remembered so the
    // editor restores the same view when the movie is reopened.
    val lastTimelineOffset: Float = DEFAULT_TIMELINE_OFFSET,
    // Last timeline zoom (pixels per second) used for this movie, remembered so the editor
    // restores the same zoom level when the movie is reopened.
    val lastTimelineZoom: Float = DEFAULT_TIMELINE_ZOOM
) {
    companion object {
        const val DEFAULT_ASPECT_RATIO: String = "16:9"
        const val DEFAULT_TIMELINE_OFFSET: Float = 0f
        const val DEFAULT_TIMELINE_ZOOM: Float = 20f
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
 * One page of the global asset library (`GET /api/library`). [items] is the current slice,
 * [total] is how many assets match the filters overall, and [hasMore] is true when more pages
 * remain after [offset] + [items].size.
 */
@Serializable
data class LibraryPage(
    val items: List<Asset> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val limit: Int = 0,
    val hasMore: Boolean = false,
)

/**
 * A previously generated version of an asset's media. Every regeneration pushes the old media
 * onto the asset's [Asset.history] so the user can restore any prior version at any time.
 */
@Serializable
data class AssetVersion(
    val ossUrl: String,
    val durationSeconds: Double,
    val createdAt: Long,
    val prompt: String? = null,
    // The asset's media type when this version was current. Regenerations may convert an asset
    // between types (e.g. image -> video), so restoring a version restores its type too.
    // Nullable for versions saved before this field existed (treated as the asset's current type).
    val type: AssetType? = null
)

/**
 * One entry in an [Asset]'s AI-cost ledger: a single AI call made while generating (or
 * regenerating) the asset's media. Every AI call connected to the asset is recorded here so the
 * asset dialog can show the running total cost in both tokens and USD, alongside each call's own
 * token usage, per-token price and resulting USD cost.
 */
@Serializable
data class AiLedgerEntry(
    // Human-readable details of the AI call, e.g. "Generated video (wan3.0-video)".
    val description: String,
    // The AI model the call used.
    val model: String,
    // Total tokens the call consumed.
    val tokens: Long,
    // USD price charged per token for this call's model.
    val costPerToken: Double,
    val createdAt: Long = 0
) {
    /** This call's cost in USD: [tokens] × [costPerToken]. */
    val costUsd: Double get() = tokens * costPerToken
}

/** Total tokens across every AI call recorded in an [Asset]'s ledger. */
fun List<AiLedgerEntry>.totalTokens(): Long = sumOf { it.tokens }

/** Total USD cost across every AI call recorded in an [Asset]'s ledger. */
fun List<AiLedgerEntry>.totalCostUsd(): Double = sumOf { it.costUsd }

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
    // For MUSIC assets generated with vocals: the lyrics returned by the music model (Fun-Music),
    // including its section markers (e.g. "[verse]"). Null for instrumental tracks or media that
    // carries no lyrics.
    val lyrics: String? = null,
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
    // AI-cost ledger: one entry per AI call made while generating/regenerating this asset's media.
    // The asset dialog shows the running total (tokens and USD). Appended to on every generation.
    val ledger: List<AiLedgerEntry> = emptyList(),
    // For VOICE assets: the preset or cloned voice used for TTS.
    val voice: String? = null,
    // For IMAGE/VIDEO assets: the visual style ([VisualStyle.id]) applied when this media was
    // generated, so regenerations can restore it and the library can show which style was used.
    // Null when no style was selected (or the asset predates this field).
    val styleId: String? = null,
    // For TEXT assets: whether this text is a media placeholder (rendered as a plain description
    // card and fillable with generated media) rather than a first-class text element rendered with
    // its own styling. Ignored for non-TEXT assets. Defaults true so existing dropped/described
    // text (and any other description-only placeholder) keeps behaving as a placeholder.
    val isPlaceholder: Boolean = true,
    val createdAt: Long = 0
) {
    /** True when this asset has no media yet — it exists as a textual description only. */
    val isDescriptionOnly: Boolean get() = ossUrl.isBlank()

    /**
     * True when this asset is a media placeholder awaiting generation: it has no media yet AND is
     * still flagged as a placeholder. A TEXT asset whose placeholder flag is turned off is a
     * first-class text element (see [isTextElement]) rather than a placeholder.
     */
    val isPlaceholderAsset: Boolean get() = isDescriptionOnly && isPlaceholder

    /**
     * True when this is a TEXT asset rendered as a styled text element — its own color, font, size
     * and background (see [TextConfig]) — rather than a media placeholder. Such an asset has no
     * media of its own; its text comes from [description]/[aiPrompt].
     */
    val isTextElement: Boolean get() = type == AssetType.TEXT && isDescriptionOnly && !isPlaceholder

    /**
     * True when this asset is associated with the movie identified by [movieId] (used by the asset
     * details "Add to movie" / "Remove from movie" actions and the library's "This movie" filter).
     */
    fun isAssociatedWithMovie(movieId: String?): Boolean =
        movieId != null && this.movieId == movieId
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
    // The id of the in-flight asynchronous provider task (e.g. an Alibaba Model Studio /
    // DashScope task_id) this job is currently waiting on, when the generation is executed as a
    // remote async task. Persisted while the task is running and cleared once it completes, so a
    // job interrupted by a server crash can be resumed by re-polling the same remote task on
    // startup instead of being restarted from scratch (see JobRecoveryService). Null for jobs
    // with no resumable remote task (synchronous generations, renders, skeleton planning).
    val taskId: String? = null,
    val createdAt: Long = 0,
    // The library asset this generation was launched from (the asset whose details dialog opened
    // the generate/regenerate dialog), when applicable. Lets the UI show a "generating" spinner on
    // that asset's actions while any of its generations are still in flight, without the generate
    // dialogs having to talk to the asset dialog directly. Null for jobs not started from an asset
    // (e.g. a fresh library generation, final renders, skeleton planning).
    val sourceAssetId: String? = null
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
 * A saved character the user can reference in AI generations: a name, a text description,
 * an optional [mainLanguage] they speak by default, an optional [voiceId] from the Voice Library,
 * and up to [MAX_REFERENCE_IMAGES] reference images. When [mainLanguage] is set, video generation
 * prompts append `"<name> speaks in <mainLanguage> unless otherwise specified."`. When [voiceId]
 * is set, video generation can synthesize prompt dialogue with that voice and attach it as
 * driving audio so the character sounds consistent across clips.
 */
@Serializable
data class Character(
    val id: String,
    val name: String,
    val description: String,
    val referenceImages: List<String> = emptyList(),
    // Default spoken language for this character (free-form, e.g. "English", "Mandarin Chinese").
    // Blank means no language preference is injected into generation prompts. Characters saved
    // before this field existed decode as blank.
    val mainLanguage: String = "",
    /**
     * Voice Library synthesis id used for this character's in-video speech: a built-in preset
     * id, or a clone/design [VoiceClone.qwenVoiceId] / [VoiceDesign.qwenVoiceId]. Blank means no
     * linked voice (visual-only R2V). Characters saved before this field existed decode as blank.
     */
    val voiceId: String = "",
    // The movie this character was created for; drives the "This movie" library filter, mirroring
    // Asset.movieId. Null for characters saved before this field existed (always shown).
    val movieId: String? = null,
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
    // The movie this scene was created for; drives the "This movie" library filter, mirroring
    // Asset.movieId. Null for scenes saved before this field existed (always shown).
    val movieId: String? = null,
    val createdAt: Long = 0
) {
    companion object {
        const val MAX_REFERENCE_IMAGES = 3
    }
}

/**
 * A saved visual style the user can apply to image/video generations so independent media keeps a
 * consistent look: a short [name] and free-form [style] text that is appended to the generation
 * prompt (see [GenerationSetup.styleId]).
 */
@Serializable
data class VisualStyle(
    val id: String,
    val name: String,
    // The style description appended to visual generation prompts, e.g.
    // "semi-realistic cute/beautiful anime with faint outlines...".
    val style: String,
    // The movie this style was created for; drives the "This movie" library filter, mirroring
    // Asset.movieId. Null for styles saved without a movie association (always shown).
    val movieId: String? = null,
    val createdAt: Long = 0
)

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
 * A user-created "designed" voice (CosyVoice Voice Design): instead of supplying an audio sample
 * like a [VoiceClone], the user describes the voice in natural language (e.g. "a warm, gravelly
 * old storyteller") and the model synthesizes a matching custom voice. The [qwenVoiceId] is the
 * enrolled voice id TTS requests reference; [description] is the natural-language prompt used to
 * design it.
 */
@Serializable
data class VoiceDesign(
    val id: String,
    val name: String,
    val description: String,
    val qwenVoiceId: String,
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
 * A rendered movie that finished encoding but could NOT be uploaded to object storage (Alibaba
 * OSS) — e.g. a transient network/credentials failure. The finished file is copied to a durable
 * local directory ([localFilePath]) and this record captures everything needed to finish the
 * render later: which [jobId]/[movieId] it belongs to, the intended storage [objectKey], and the
 * render metadata ([durationSeconds]/[aspectRatio]) that will populate the eventual [RenderRecord].
 *
 * A background retry worker periodically re-attempts the upload; [attempts]/[lastAttemptAt]/
 * [lastError] track its progress so failures are visible and back-off can be applied.
 */
@Serializable
data class PendingRenderUpload(
    val id: String,
    val jobId: String,
    val movieId: String,
    // The object-storage key the file should be uploaded under (kept stable across retries so a
    // half-finished upload is simply overwritten rather than orphaned).
    val objectKey: String,
    // Absolute path to the durably-stored rendered file awaiting upload.
    val localFilePath: String,
    val durationSeconds: Double,
    val aspectRatio: String,
    // How many upload attempts have been made so far (0 before the first retry).
    val attempts: Int = 0,
    // When the last upload attempt ran (epoch millis; 0 if never retried yet).
    val lastAttemptAt: Long = 0,
    // The most recent upload failure reason, for diagnostics/visibility.
    val lastError: String? = null,
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
 * A studio-wide tip: a short piece of reusable advice the user jots down (e.g. workflow tricks,
 * reminders). Tips are global — not tied to any movie — searchable, and shown newest first.
 */
@Serializable
data class Tip(
    val id: String,
    val title: String,
    val content: String = "",
    val read: Boolean = false,
    val createdAt: Long = 0
)

/**
 * Workflow status of a reported [Issue]. Independent of the issue's open/closed state ([Issue.isOpen]):
 * an issue can be marked (e.g.) resolved while still open, or reopened after being closed.
 */
enum class IssueStatus {
    NEW,
    IN_PROGRESS,
    RESOLVED,
    WONT_FIX,
}

/** Human-readable label for an issue status. */
fun IssueStatus.displayName(): String = when (this) {
    IssueStatus.NEW -> "New"
    IssueStatus.IN_PROGRESS -> "In progress"
    IssueStatus.RESOLVED -> "Resolved"
    IssueStatus.WONT_FIX -> "Won't fix"
}

/**
 * A user-reported issue: much like a [Tip] it is studio-wide (not tied to any movie), searchable
 * and carries a [title] and [description]. On top of that it tracks a workflow [status] and an
 * [isOpen] flag. Open issues are the ones surfaced by the dashboard's issue counter; the issue
 * detail dialog lets the user edit the details, change the status and open/close the issue.
 */
@Serializable
data class Issue(
    val id: String,
    val title: String,
    val description: String = "",
    val status: IssueStatus = IssueStatus.NEW,
    val isOpen: Boolean = true,
    val createdAt: Long = 0
)

/**
 * A built-in ("default") voice that ships with the studio — one of the Qwen3-TTS preset voices.
 * [id] is the value TTS requests send as the `voice`, [name] is the display name, [languages] are
 * the spoken languages the voice supports (Qwen3-TTS is multilingual and also covers several
 * Chinese dialects), [description] is a short human blurb and [gender] is "female"/"male"/"".
 * Cloned and designed voices from the user's Voice Library are offered alongside these.
 */
@Serializable
data class VoicePreset(
    val id: String,
    val name: String,
    val languages: List<String> = emptyList(),
    val description: String = "",
    val gender: String = ""
)

/** The languages the standard (non-dialect) Qwen3-TTS voices can speak. */
private val QWEN_TTS_MULTILINGUAL: List<String> = listOf(
    "Chinese", "English", "Spanish", "French", "German",
    "Italian", "Portuguese", "Japanese", "Korean", "Russian"
)

/**
 * The catalog of Qwen-TTS default voices with their spoken languages — the studio's built-in Voice
 * Library "Default Voices". This mirrors the documented non-real-time qwen-tts voice list (the
 * models are an enumerated set rather than a queryable endpoint); [AIGenerationService.listVoicePresets]
 * returns it.
 */
val QWEN_VOICE_CATALOG: List<VoicePreset> = listOf(
    VoicePreset("Cherry", "Cherry", QWEN_TTS_MULTILINGUAL, "Warm, friendly female voice", "female"),
    VoicePreset("Serena", "Serena", QWEN_TTS_MULTILINGUAL, "Clear, warm female voice", "female"),
    VoicePreset("Ethan", "Ethan", QWEN_TTS_MULTILINGUAL, "Bright, upbeat male voice", "male"),
    VoicePreset("Chelsie", "Chelsie", QWEN_TTS_MULTILINGUAL, "Friendly, expressive female voice", "female"),
    VoicePreset("Momo", "Momo", QWEN_TTS_MULTILINGUAL, "Lively, youthful female voice", "female"),
    VoicePreset("Vivian", "Vivian", QWEN_TTS_MULTILINGUAL, "Soft, gentle female voice", "female"),
    VoicePreset("Moon", "Moon", QWEN_TTS_MULTILINGUAL, "Calm, rounded female voice", "female"),
    VoicePreset("Maia", "Maia", QWEN_TTS_MULTILINGUAL, "Natural, conversational female voice", "female"),
    VoicePreset("Kai", "Kai", QWEN_TTS_MULTILINGUAL, "Confident, conversational male voice", "male"),
    VoicePreset("Nofish", "Nofish", QWEN_TTS_MULTILINGUAL, "Relaxed, casual male voice", "male"),
    VoicePreset("Bella", "Bella", QWEN_TTS_MULTILINGUAL, "Bright, polished female voice", "female"),
    VoicePreset("Jennifer", "Jennifer", QWEN_TTS_MULTILINGUAL, "Poised, professional female voice", "female"),
    VoicePreset("Ryan", "Ryan", QWEN_TTS_MULTILINGUAL, "Smooth, mellow male voice", "male"),
    VoicePreset("Katerina", "Katerina", QWEN_TTS_MULTILINGUAL, "Elegant, expressive female voice", "female"),
    VoicePreset("Aiden", "Aiden", QWEN_TTS_MULTILINGUAL, "Balanced, friendly male voice", "male"),
    VoicePreset("Mia", "Mia", QWEN_TTS_MULTILINGUAL, "Light, cheerful female voice", "female"),
    VoicePreset("Mochi", "Mochi", QWEN_TTS_MULTILINGUAL, "Playful, animated female voice", "female"),
    VoicePreset("Bellona", "Bellona", QWEN_TTS_MULTILINGUAL, "Strong, articulate female voice", "female"),
    VoicePreset("Vincent", "Vincent", QWEN_TTS_MULTILINGUAL, "Warm, steady male voice", "male"),
    VoicePreset("Bunny", "Bunny", QWEN_TTS_MULTILINGUAL, "Cute, energetic female voice", "female"),
    VoicePreset("Neil", "Neil", QWEN_TTS_MULTILINGUAL, "Clear, narrator-style male voice", "male"),
    VoicePreset("Elias", "Elias", QWEN_TTS_MULTILINGUAL, "Measured, lecturer-style male voice", "male"),
    VoicePreset("Arthur", "Arthur", QWEN_TTS_MULTILINGUAL, "Classic, composed male voice", "male"),
    VoicePreset("Nini", "Nini", QWEN_TTS_MULTILINGUAL, "Sweet, bright female voice", "female"),
    VoicePreset("Seren", "Seren", QWEN_TTS_MULTILINGUAL, "Smooth, relaxed female voice", "female"),
    VoicePreset("Pip", "Pip", QWEN_TTS_MULTILINGUAL, "Light, quirky male voice", "male"),
    VoicePreset("Stella", "Stella", QWEN_TTS_MULTILINGUAL, "Polished, confident female voice", "female"),
    VoicePreset("Bodega", "Bodega", QWEN_TTS_MULTILINGUAL, "Warm, characterful male voice", "male"),
    VoicePreset("Sonrisa", "Sonrisa", QWEN_TTS_MULTILINGUAL, "Sunny, expressive female voice", "female"),
    VoicePreset("Alek", "Alek", QWEN_TTS_MULTILINGUAL, "Direct, articulate male voice", "male"),
    VoicePreset("Dolce", "Dolce", QWEN_TTS_MULTILINGUAL, "Soft, melodic female voice", "female"),
    VoicePreset("Sohee", "Sohee", QWEN_TTS_MULTILINGUAL, "Gentle, natural female voice", "female"),
    VoicePreset("Lenn", "Lenn", QWEN_TTS_MULTILINGUAL, "Friendly, modern male voice", "male"),
    VoicePreset("Emilien", "Emilien", QWEN_TTS_MULTILINGUAL, "Smooth, European male voice", "male"),
    VoicePreset("Andre", "Andre", QWEN_TTS_MULTILINGUAL, "Deep, composed male voice", "male"),
)

/**
 * Voice ids of the built-in Qwen presets, kept as a plain list for defaults/fallbacks (e.g. the
 * default narration voice). Derived from [QWEN_VOICE_CATALOG].
 */
val QWEN_VOICE_PRESETS: List<String> = QWEN_VOICE_CATALOG.map { it.id }

/**
 * Default narration voice when a movie has no remembered [Movie.lastVoice] yet (and no asset/setup
 * voice to restore). Matches the first entry of [QWEN_VOICE_CATALOG].
 */
const val DEFAULT_VOICE_ID: String = "Cherry"

/**
 * All selectable voices in the Voice Library: the built-in Qwen [presets] (Default Voices), the
 * user's [clones] (Cloned Voices) and their [designs] (Voice Design voices).
 */
@Serializable
data class VoiceOptions(
    val presets: List<VoicePreset> = emptyList(),
    val clones: List<VoiceClone> = emptyList(),
    val designs: List<VoiceDesign> = emptyList()
)

/**
 * Distinct TTS voice ids already used by VOICE assets belonging to [movieId], most-recently-used
 * first (by asset [Asset.createdAt]). Drives the Voice Library's "From this movie" section so the
 * user can re-pick voices already featured in the open movie without scrolling the full catalog.
 * Blank / missing [movieId] yields an empty list.
 */
fun voicesUsedInMovie(assets: List<Asset>, movieId: String?): List<String> {
    if (movieId.isNullOrBlank()) return emptyList()
    val seen = linkedSetOf<String>()
    assets
        .asSequence()
        .filter { it.type == AssetType.VOICE && it.movieId == movieId }
        .sortedByDescending { it.createdAt }
        .mapNotNull { it.voice?.takeIf { v -> v.isNotBlank() } }
        .forEach { seen.add(it) }
    return seen.toList()
}

/** A short, friendly line spoken when the user previews ("samples") a voice. */
const val VOICE_SAMPLE_TEXT: String =
    "Hi there! This is a preview of how I sound. Let's make a great movie together."

// TTS speech-rate (speed) and pitch bounds, shared by the sliders (client) and the request
// builders (server). Both Qwen (preset voices) and CosyVoice (cloned/designed voices) accept a
// 0.5×–2.0× multiplier for each, with 1.0× being the voice's natural delivery.
const val TTS_MIN_SPEED: Double = 0.5
const val TTS_MAX_SPEED: Double = 2.0
const val TTS_DEFAULT_SPEED: Double = 1.0
const val TTS_MIN_PITCH: Double = 0.5
const val TTS_MAX_PITCH: Double = 2.0
const val TTS_DEFAULT_PITCH: Double = 1.0

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
    VIGNETTE,
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
    TransitionType.VIGNETTE -> "Vignette"
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
 * - [vignetteRevealFraction]: a centered, aspect-matched *elliptical* reveal with a soft (feathered)
 *   edge — the "vignette" iris (0 = nothing shown, 1 = fully revealed / no mask). Unlike the hard
 *   [revealRadiusFraction] circle, the oval matches the stage aspect and the reveal radius grows a
 *   little past the corners so the whole frame ends fully opaque.
 * - [pixelateFraction]: mosaic amount (0 = crisp, 1 = maximally blocky).
 * - [noiseFraction]: grain/dissolve amount (0 = clean, 1 = fully speckled) applied to the clip's
 *   alpha, so the clip emerges from noise.
 * - [voronoiFraction]: voronoi-cell amount (0 = crisp, 1 = coarse cells) — the clip is sampled at
 *   the nearest random cell seed, so it resolves out of cellular blocks.
 *
 * The GPU-composited WebGL preview can express every primitive (including [pixelateFraction],
 * [noiseFraction] and [voronoiFraction]) because it runs a fragment shader over the frame. The
 * default DOM `<video>` + Compose preview can only express [alpha], [translateXFraction] /
 * [translateYFraction] and [revealRadiusFraction], so there the textured transitions fall back to
 * their accompanying [alpha] cross-fade; see `docs/Transitions.md`.
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
    val vignetteRevealFraction: Float = 1f,
    val pixelateFraction: Float = 0f,
    val noiseFraction: Float = 0f,
    val voronoiFraction: Float = 0f
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
 * - [TransitionType.VIGNETTE]: a centered, aspect-matched elliptical reveal with a soft edge that
 *   grows from nothing to full (`vignetteRevealFraction = progress`). The GPU-composited WebGL
 *   preview and the FFmpeg render draw the real oval iris; the default DOM preview can only express
 *   the accompanying `alpha = progress` cross-fade, so it falls back to a plain fade.
 * - [TransitionType.PIXELATE]: the clip resolves out of large mosaic blocks
 *   (`pixelateFraction = 1 - progress`) while it cross-fades in (`alpha = progress`).
 * - [TransitionType.NOISE]: the clip emerges from grain/dissolve speckle
 *   (`noiseFraction = 1 - progress`) while it cross-fades in (`alpha = progress`).
 * - [TransitionType.VORONOI]: the clip resolves out of voronoi cells
 *   (`voronoiFraction = 1 - progress`) while it cross-fades in (`alpha = progress`).
 * - [TransitionType.ALPHA]: a plain cross-fade (`alpha = progress`).
 *
 * The WebGL preview and the FFmpeg render both apply the textured [pixelateFraction] /
 * [noiseFraction] / [voronoiFraction] amounts; the default DOM preview can only express the
 * accompanying [alpha] cross-fade (see `docs/Transitions.md`).
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
        TransitionType.VIGNETTE -> TransitionVisual(alpha = p, vignetteRevealFraction = p)
        TransitionType.PIXELATE -> TransitionVisual(alpha = p, pixelateFraction = 1f - p)
        TransitionType.NOISE -> TransitionVisual(alpha = p, noiseFraction = 1f - p)
        TransitionType.VORONOI -> TransitionVisual(alpha = p, voronoiFraction = 1f - p)
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
    // OSS-hosted .ttf of the chosen Google Fonts variant; empty for the bundled/default families.
    val fontUrl: String = "",
    // Weight/italic of the chosen variant. Captions always rendered bold historically, so 700.
    val fontWeight: Int = 700,
    val fontItalic: Boolean = false,
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
 * Font families offered by the text-asset font chooser. Same set as the caption chooser plus the
 * generic families, so a first-class text element can pick a serif/monospace look too.
 */
val TEXT_FONT_FAMILIES: List<String> = listOf(
    "Default", "Asap", "Yuyu"
)

/**
 * The app-bundled font families (plus "Default"), offered as "Studio fonts" in the font picker.
 * Any family outside this list is a Google Fonts family carried by [TextConfig.fontUrl] /
 * [CaptionConfig.fontUrl].
 */
val BUILT_IN_FONT_FAMILIES: List<String> = listOf("Default", "Asap", "Yuyu")

// ------------------------------------------------------------------------------ Google Fonts

/**
 * One family of the Google Fonts catalog, as served to the font picker. [variants] use the
 * Google Fonts naming ("regular", "italic", "700", "700italic", ...) so the picker only offers
 * weights/italics the family actually ships. [previewUrl] is the small "menu" subset of the font
 * (family-name glyphs only), used to render the family name in its own typeface in the picker.
 */
@Serializable
data class FontCatalogEntry(
    val family: String,
    val category: String = "",
    val variants: List<String> = emptyList(),
    val subsets: List<String> = emptyList(),
    val previewUrl: String = ""
)

/** Result of a font-catalog search: matching families plus the filter option lists. */
@Serializable
data class FontSearchResponse(
    val fonts: List<FontCatalogEntry> = emptyList(),
    val subsets: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val totalMatches: Int = 0,
    // False when the Google Fonts catalog is unavailable (no API key and no cached copy).
    val catalogAvailable: Boolean = true
)

/** The user's pinned and recently used font families, shown as picker sections. */
@Serializable
data class FontPrefsResponse(
    val pinned: List<FontCatalogEntry> = emptyList(),
    val recent: List<FontCatalogEntry> = emptyList()
)

/**
 * A Google Fonts variant the server has downloaded once, re-hosted on OSS and persisted, so it is
 * reused across all movies (both by the preview and by the FFmpeg export) without re-downloading.
 */
@Serializable
data class StudioFont(
    val id: String = "",
    val family: String = "",
    val variant: String = "regular",
    val url: String = "",
    val createdAt: Long = 0
)

/** Per-family picker preferences: pinned state and last-used time (drives "Recently used"). */
@Serializable
data class FontPref(
    val id: String = "",
    val family: String = "",
    val pinned: Boolean = false,
    val lastUsedAt: Long = 0,
    val createdAt: Long = 0
)

/** The weight encoded in a Google Fonts variant name ("regular"/"italic" → 400, "700italic" → 700). */
fun fontVariantWeight(variant: String): Int = variant.removeSuffix("italic").toIntOrNull() ?: 400

/** Whether a Google Fonts variant name is italic ("italic", "500italic", ...). */
fun fontVariantItalic(variant: String): Boolean = variant.endsWith("italic")

/** The Google Fonts variant name for a weight/italic pair (400 upright → "regular"). */
fun fontVariantName(weight: Int, italic: Boolean): String = when {
    weight == 400 && italic -> "italic"
    weight == 400 -> "regular"
    italic -> "${weight}italic"
    else -> "$weight"
}

/** Human-readable label for a font weight, matching the Google Fonts UI naming. */
fun fontWeightLabel(weight: Int): String = when (weight) {
    100 -> "Thin 100"
    200 -> "ExtraLight 200"
    300 -> "Light 300"
    400 -> "Regular 400"
    500 -> "Medium 500"
    600 -> "SemiBold 600"
    700 -> "Bold 700"
    800 -> "ExtraBold 800"
    900 -> "Black 900"
    else -> "$weight"
}

/** Fully-transparent color, the default [TextConfig.backgroundColor] so nothing is drawn behind text. */
const val TRANSPARENT_COLOR: String = "#00000000"

/**
 * Rendering settings for a first-class TEXT element, stored inside [Clip.effectsConfig] under the
 * `"text"` key and edited in the `ClipInspector`. The text content itself comes from the asset's
 * description/prompt; this only styles it.
 *
 * [color] is the text color (`#RRGGBB` / `#AARRGGBB`), [fontFamily] one of [TEXT_FONT_FAMILIES],
 * [fontSizeSp] the size relative to a 480px-tall reference canvas (scaled to the preview stage and
 * the render height so the preview matches the export), and [backgroundColor] the fill drawn behind
 * the text — transparent by default, so lower clips / the black stage show through.
 */
@Serializable
data class TextConfig(
    val color: String = "#FFFFFF",
    val fontFamily: String = "Default",
    // OSS-hosted .ttf of the chosen Google Fonts variant; empty for the bundled/default families.
    val fontUrl: String = "",
    // Weight/italic of the chosen variant. Text elements always rendered bold historically, so 700.
    val fontWeight: Int = 700,
    val fontItalic: Boolean = false,
    val fontSizeSp: Int = 48,
    val backgroundColor: String = TRANSPARENT_COLOR
)

/** The reference canvas height (px) [TextConfig.fontSizeSp] and [CaptionConfig.fontSizeSp] are relative to. */
const val TEXT_REFERENCE_HEIGHT: Double = 480.0

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

/**
 * Extra linear gain applied on top of a voice-track clip's [EffectsConfig.volume] / envelope in
 * live preview and FFmpeg export, so dialogue sits above beds at default faders.
 */
const val VOICE_VOLUME_BOOST: Double = 2.0

/** Playback gain multiplier for clips on [trackType] (voice is boosted; everything else is unity). */
fun trackVolumeBoost(trackType: TrackType): Double =
    if (trackType == TrackType.VOICE) VOICE_VOLUME_BOOST else 1.0

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
    // Styling for a first-class TEXT element (color/font/size/background); null for non-text clips.
    val text: TextConfig? = null,
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

/**
 * True when a clip on a track of [trackType] backed by an asset of [assetType] carries an audio
 * stream the volume control can shape: everything on the audio tracks (music / voice / effects),
 * plus video media on the video track. Still images and description-only text cards have no audio,
 * so they are excluded (a null [assetType] on the video track is treated as "not a video asset").
 */
fun clipCarriesAudio(trackType: TrackType, assetType: AssetType?): Boolean = when (trackType) {
    TrackType.MUSIC, TrackType.VOICE, TrackType.EFFECTS -> true
    TrackType.VIDEO -> assetType == AssetType.VIDEO
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
 * Video always uses the unified Wan 3.0 model ([DEFAULT_VIDEO_MODEL_ID]); the attached
 * inputs only change the request's `input.media` (T2V / I2V / R2V / video-edit):
 * - video + a base video -> video edit (`reference` / source video media)
 * - video + reference images / characters / scenes -> R2V
 * - video + a start image -> I2V
 * - video + prompt only -> T2V
 * - image -> text-to-image (or image edit when a base image is attached)
 *
 * For image generation the user additionally picks the concrete model ([model], one of
 * [SUPPORTED_IMAGE_MODELS]); a blank [model] lets the server use its configured default.
 */
@Serializable
data class GenerationSetup(
    val kind: String, // "video" | "image" | "music" | "tts" | "sfx"
    val prompt: String = "",
    val negativePrompt: String = "",
    val imageUrl: String? = null,
    // An optional end image for image-to-video (I2V): the clip is generated so it starts on the
    // start image ([imageUrl], the first frame) and ends on this one (the last frame). Only used
    // for I2V, i.e. alongside a start [imageUrl].
    val endImageUrl: String? = null,
    // Optional video sources for the I2V start/end frames. Instead of a still image, the user can
    // pick a video: its LAST frame becomes the start frame ([startFrameVideoUrl]) and another
    // video's FIRST frame becomes the end frame ([endFrameVideoUrl]) — so a clip can continue from
    // where another one ended, or lead into where another one begins. The server extracts the
    // still frame from the video and feeds it as a regular image to I2V generation (see
    // QwenAIService.executeVideo); the resolved frame fills [imageUrl]/[endImageUrl] at request
    // time. When set, [startFrameVideoUrl] takes precedence over [imageUrl] for the start frame
    // (and [endFrameVideoUrl] over [endImageUrl] for the end frame).
    val startFrameVideoUrl: String? = null,
    val endFrameVideoUrl: String? = null,
    // A base video to edit (same wan3.0-video model; request switches to video-edit media).
    val videoUrl: String? = null,
    val referenceImages: List<String> = emptyList(),
    val characterIds: List<String> = emptyList(),
    val sceneIds: List<String> = emptyList(),
    // Optional visual style ([VisualStyle.id]) applied to image/video generation. The style text
    // is appended to the prompt after character/scene/reference additions as
    // "\n\nVisual style: <style text>\n\n". Blank/null means no style.
    val styleId: String? = null,
    val durationSeconds: Double = 5.0,
    val resolution: String = "1280*720",
    // Image-generation model id (one of [SUPPORTED_IMAGE_MODELS], e.g. "wan3.0-image-pro"); blank
    // lets the server use its configured default. Only meaningful for image generation.
    val model: String = "",
    // Music-specific options.
    val lyric: String = "",
    val theme: String = "",
    val instrumental: Boolean = false,
    // Preferred vocal gender for Fun-Music ("female" | "male"); blank lets the model decide.
    // Ignored when [instrumental] is set (no vocals to gender).
    val gender: String = "",
    // Voice-specific options.
    val voice: String = "",
    // Optional voice instructions for TTS (Qwen instruct): how the line should be delivered,
    // e.g. "happy", "sad", "excited", "whispering, slightly out of breath".
    val instructions: String = "",
    // TTS speech-rate (speed) and pitch multipliers, adjusted before generating a voiceover.
    // 1.0 = the voice's natural speed/pitch. Applied to Default (Qwen), Cloned and Voice Design
    // (CosyVoice) voices alike: they ride along as the `rate`/`pitch` synthesis parameters both
    // APIs accept, clamped to [TTS_MIN_SPEED]..[TTS_MAX_SPEED] / [TTS_MIN_PITCH]..[TTS_MAX_PITCH].
    // Only meaningful for tts.
    val speed: Double = TTS_DEFAULT_SPEED,
    val pitch: Double = TTS_DEFAULT_PITCH,
    // Sound-effect-specific options: which generation mode produces the audio (see
    // [SUPPORTED_SFX_MODELS]).
    val sfxModel: String = "wan"
) {
    /** The WAN model family this setup resolves to (for video/image kinds). */
    fun resolveVideoModelKind(): String = when {
        kind != "video" -> kind
        videoUrl != null -> "videoedit"
        referenceImages.isNotEmpty() || characterIds.isNotEmpty() || sceneIds.isNotEmpty() -> "r2v"
        // A still start image or a video whose last frame is used as the start frame both drive I2V.
        imageUrl != null || startFrameVideoUrl != null -> "i2v"
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

/** Minimum video generation length in seconds (WAN 3.0). */
const val MIN_VIDEO_DURATION_SECONDS: Int = 2

/** Maximum video generation length in seconds (WAN 3.0 supports up to 30s). */
const val MAX_VIDEO_DURATION_SECONDS: Int = 30

/**
 * Video generation sizes the studio offers. Wan 3.0's API takes a resolution tier
 * (`480P` / `720P` / `1080P`) plus a ratio rather than these pixel sizes; [wanVideoResolutionTier]
 * and [wanVideoRatio] map a picked size onto those parameters. 2K studio presets collapse to 1080P
 * because the video API does not expose a 2K tier.
 */
val SUPPORTED_VIDEO_SIZES: List<String> = listOf(
    // 720p
    "1280*720", "720*1280", "960*960", "1680*720", "720*1680",
    // 1080p
    "1920*1080", "1080*1920", "1440*1440", "2520*1080", "1080*2520",
    // 2K studio presets (mapped down to 1080P on the Wan 3.0 video API)
    "2560*1440", "1440*2560", "1920*1920", "3360*1440", "1440*3360",
    // 480p
    "832*480", "480*832", "624*624", "1120*480", "480*1120"
)

/**
 * Official Wan 3.0 video model id (DashScope). T2V / I2V / R2V / video-edit are media inputs on
 * this one model — there is no `wan3.0-t2v` family. `wan3.0-video-prime` is the faster sibling
 * and can be selected via `QWEN_VIDEO_MODEL`.
 */
const val DEFAULT_VIDEO_MODEL_ID: String = "wan3.0-video"

/**
 * Aspect ratios the Wan 3.0 video API accepts (plus `adaptive` for anything else, e.g. 21:9).
 */
val WAN_VIDEO_RATIOS: List<String> = listOf("16:9", "9:16", "1:1", "4:3", "3:4")

/**
 * Maps a studio "W*H" size onto Wan 3.0's `parameters.resolution` tier. 2K presets collapse to
 * `1080P` because the video API only documents 480P / 720P / 1080P.
 */
fun wanVideoResolutionTier(size: String): String {
    val (width, height) = parseResolution(size) ?: return "720P"
    val shortEdge = minOf(width, height)
    return when {
        shortEdge >= 1080 -> "1080P"
        shortEdge >= 720 -> "720P"
        else -> "480P"
    }
}

/**
 * Maps a studio "W*H" size onto Wan 3.0's `parameters.ratio`. Closest official ratio wins when
 * it is within 5%; otherwise `adaptive` (used for 21:9 ultrawide and malformed sizes).
 */
fun wanVideoRatio(size: String): String {
    val (width, height) = parseResolution(size) ?: return "adaptive"
    val ratio = width.toFloat() / height.toFloat()
    val match = WAN_VIDEO_RATIOS.minByOrNull { candidate ->
        val target = aspectRatioToFloat(candidate)
        if (target > ratio) target / ratio else ratio / target
    } ?: return "adaptive"
    val target = aspectRatioToFloat(match)
    val closeness = if (target > ratio) target / ratio else ratio / target
    return if (closeness <= 1.05f) match else "adaptive"
}

/**
 * Image generation sizes offered by the text-to-image / image-edit models. Includes every
 * [SUPPORTED_VIDEO_SIZES] tier (so a movie's chosen size lines up across image and video
 * generations) plus the larger sizes the Qwen image models additionally support.
 */
val SUPPORTED_IMAGE_SIZES: List<String> = listOf(
    "1024*1024", "1280*720", "720*1280", "768*1024", "1024*768",
    "960*960", "1920*1080", "1080*1920", "1440*1440",
    "832*480", "480*832", "624*624",
    "1680*720", "720*1680", "2520*1080", "1080*2520", "1120*480", "480*1120",
    // 2K video-aligned tiers (shared with [SUPPORTED_VIDEO_SIZES]).
    "2560*1440", "1440*2560", "1920*1920", "3360*1440", "1440*3360",
    "1328*1328", "1664*928", "928*1664", "1472*1140", "1140*1472"
)

// ---------------------------------------------------------------------------------------------
// Image generation models (selectable text-to-image / image-to-image models)
// ---------------------------------------------------------------------------------------------

/**
 * A selectable text-to-image / image-to-image model offered in the image generation dialog,
 * together with its resolution capabilities. Both the [presetResolutions] and any custom
 * resolution the user types must satisfy the model's per-dimension and total-pixel limits (see
 * [validateResolutionForModel]).
 *
 * Every preset resolution matches one of [SUPPORTED_ASPECT_RATIOS] (or its portrait inverse, e.g.
 * 9:16 for 16:9) so generated images crop as little as possible against a movie's aspect ratio.
 * Each model exposes 2 square, 4 landscape and 4 portrait presets, scaled to what the model can
 * actually produce.
 */
@Serializable
data class ImageModel(
    // DashScope model id sent to the API, e.g. "wan3.0-image-pro".
    val id: String,
    // User-facing name shown in the model dropdown, e.g. "Wan 3.0 Pro".
    val displayName: String,
    // Short capability blurb shown alongside the dropdown.
    val description: String,
    // Smallest total pixel count (width × height) the model accepts.
    val minPixels: Long,
    // Largest total pixel count (width × height) the model accepts.
    val maxPixels: Long,
    // Smallest value either side may take, in pixels.
    val minDimension: Int,
    // Largest value either side may take, in pixels.
    val maxDimension: Int,
    // Preset "W*H" resolutions offered for this model, grouped by orientation in the picker.
    val presetResolutions: List<String>
)

/**
 * Wan 3.0 Pro (`wan3.0-image-pro`): the top image tier, generating up to 4K (3840×2160) — the
 * largest of the three models.
 */
val IMAGE_MODEL_WAN_PRO: ImageModel = ImageModel(
    id = "wan3.0-image-pro",
    displayName = "Wan 3.0 Pro",
    description = "Highest detail, up to 4K (3840×2160).",
    minPixels = 512L * 512L,
    maxPixels = 3840L * 2160L,
    minDimension = 512,
    maxDimension = 4096,
    presetResolutions = listOf(
        // Landscape: 16:9 (4K), 16:9 (2K), 4:3, 21:9.
        "3840*2160", "2560*1440", "2880*2160", "3360*1440",
        // Portrait: 9:16, 9:16, 3:4, 9:21.
        "2160*3840", "1440*2560", "2160*2880", "1440*3360",
        // Square: 1:1, 1:1.
        "2048*2048", "1440*1440"
    )
)

/**
 * Wan 3.0 (`wan3.0-image`): the standard Wan image tier, generating up to roughly 2.5K
 * (2560×1440).
 */
val IMAGE_MODEL_WAN: ImageModel = ImageModel(
    id = "wan3.0-image",
    displayName = "Wan 3.0",
    description = "Great quality, up to 2.5K (2560×1440).",
    minPixels = 512L * 512L,
    maxPixels = 2560L * 1440L,
    minDimension = 512,
    maxDimension = 2560,
    presetResolutions = listOf(
        // Landscape: 16:9, 16:9, 4:3, 21:9.
        "1920*1080", "1280*720", "1440*1080", "2520*1080",
        // Portrait: 9:16, 9:16, 3:4, 9:21.
        "1080*1920", "720*1280", "1080*1440", "1080*2520",
        // Square: 1:1, 1:1.
        "1440*1440", "1024*1024"
    )
)

/**
 * Default image-model id (DashScope). Single source of truth for the studio's Qwen image default:
 * the catalog entry ([IMAGE_MODEL_QWEN]), client fallbacks ([imageModelById]), and server env
 * defaults ([app.moviestudio.service.QwenConfig.imageModel] / imageEditModel) all read this value.
 * Override at runtime via `QWEN_IMAGE_MODEL` / `QWEN_IMAGE_EDIT_MODEL` on the server only.
 */
const val DEFAULT_IMAGE_MODEL_ID: String = "qwen-image-3.0-pro"

/**
 * Qwen Image 3.0 Pro ([DEFAULT_IMAGE_MODEL_ID]): the studio's current default model. Its hard
 * limit is the total pixel count — between 512×512 (262k px) and 2048×2048 (4.19M px); individual
 * sides may exceed 2048px as long as the total stays inside that window. The presets use Qwen's
 * documented aspect-optimized sizes (1328×1328, 1664×928, ...) plus matching extra tiers.
 */
val IMAGE_MODEL_QWEN: ImageModel = ImageModel(
    id = DEFAULT_IMAGE_MODEL_ID,
    displayName = "Qwen Image 3.0",
    description = "Balanced quality; total pixels 512×512 up to 2048×2048.",
    minPixels = 512L * 512L,
    maxPixels = 2048L * 2048L,
    minDimension = 512,
    maxDimension = 4096,
    presetResolutions = listOf(
        // Landscape: 16:9, 16:9, 4:3, 21:9.
        "1664*928", "1536*864", "1472*1104", "2016*864",
        // Portrait: 9:16, 9:16, 3:4, 9:21.
        "928*1664", "864*1536", "1104*1472", "864*2016",
        // Square: 1:1, 1:1.
        "1328*1328", "1024*1024"
    )
)

/**
 * The selectable image-generation models, in dropdown order (Wan 3.0 Pro, Wan 3.0, Qwen Image 3.0).
 */
val SUPPORTED_IMAGE_MODELS: List<ImageModel> = listOf(IMAGE_MODEL_WAN_PRO, IMAGE_MODEL_WAN, IMAGE_MODEL_QWEN)

/** Looks up a [SUPPORTED_IMAGE_MODELS] entry by [id], falling back to the default (then first) model. */
fun imageModelById(id: String?): ImageModel =
    SUPPORTED_IMAGE_MODELS.firstOrNull { it.id.equals(id, ignoreCase = true) }
        ?: SUPPORTED_IMAGE_MODELS.firstOrNull { it.id == DEFAULT_IMAGE_MODEL_ID }
        ?: SUPPORTED_IMAGE_MODELS.first()

/** Orientation of a "W*H" resolution, used to group resolution presets in the picker. */
enum class ResolutionOrientation { LANDSCAPE, PORTRAIT, SQUARE }

/** Parses a "W*H" (or "WxH") resolution into its integer width/height, or null when malformed. */
fun parseResolution(resolution: String): Pair<Int, Int>? {
    val parts = resolution.split('*', 'x', 'X')
    val width = parts.getOrNull(0)?.trim()?.toIntOrNull()
    val height = parts.getOrNull(1)?.trim()?.toIntOrNull()
    return if (width != null && height != null && width > 0 && height > 0) width to height else null
}

/** The [ResolutionOrientation] of a "W*H" resolution (malformed input is treated as landscape). */
fun resolutionOrientation(resolution: String): ResolutionOrientation {
    val (width, height) = parseResolution(resolution) ?: return ResolutionOrientation.LANDSCAPE
    return when {
        width > height -> ResolutionOrientation.LANDSCAPE
        width < height -> ResolutionOrientation.PORTRAIT
        else -> ResolutionOrientation.SQUARE
    }
}

/** This model's preset resolutions in the given [orientation], preserving their listed order. */
fun ImageModel.presetsFor(orientation: ResolutionOrientation): List<String> =
    presetResolutions.filter { resolutionOrientation(it) == orientation }

/**
 * A short aspect-ratio label ("16:9", "9:16", ...) for a "W*H" resolution: the
 * [SUPPORTED_ASPECT_RATIOS] entry (or its portrait inverse) whose ratio is closest to the
 * resolution's own. Used to annotate preset resolutions in the picker.
 */
fun aspectRatioLabelFor(resolution: String): String {
    val (width, height) = parseResolution(resolution) ?: return ""
    val ratio = width.toFloat() / height.toFloat()
    val candidates = SUPPORTED_ASPECT_RATIOS.flatMap { r ->
        val parts = r.split(":")
        val inverse = if (parts.size == 2) "${parts[1]}:${parts[0]}" else r
        listOf(r, inverse)
    }.distinct()
    return candidates.minByOrNull { candidate ->
        val f = aspectRatioToFloat(candidate)
        if (f > ratio) f / ratio else ratio / f
    } ?: ""
}

/**
 * Validates a "W*H" [resolution] against [model]'s capabilities. Returns null when valid, or a
 * short human-readable reason it is rejected (bad format, a side out of range, or a total pixel
 * count outside the model's supported window).
 */
fun validateResolutionForModel(resolution: String, model: ImageModel): String? {
    val parsed = parseResolution(resolution)
        ?: return "Enter the resolution as WIDTH×HEIGHT, e.g. 1024×768."
    val (width, height) = parsed
    if (width < model.minDimension || height < model.minDimension) {
        return "Each side must be at least ${model.minDimension}px."
    }
    if (width > model.maxDimension || height > model.maxDimension) {
        return "Each side can be at most ${model.maxDimension}px for ${model.displayName}."
    }
    val pixels = width.toLong() * height.toLong()
    if (pixels < model.minPixels) {
        return "Too small: ${model.displayName} needs at least ${model.minPixels} total pixels " +
            "(width × height)."
    }
    if (pixels > model.maxPixels) {
        return "Too large: ${model.displayName} allows at most ${model.maxPixels} total pixels " +
            "(width × height)."
    }
    return null
}

/** True when [resolution] is a valid size for [model]. */
fun isResolutionValidForModel(resolution: String, model: ImageModel): Boolean =
    validateResolutionForModel(resolution, model) == null

/**
 * Completes a partial custom resolution from a single known dimension: given one side and a target
 * [aspectRatio] from [SUPPORTED_ASPECT_RATIOS] (e.g. "16:9"), computes the missing side so the
 * result has that aspect. [knownIsWidth] tells whether [known] is the width (else the height).
 * Returns the resulting "W*H" string.
 */
fun resolutionForAspect(known: Int, knownIsWidth: Boolean, aspectRatio: String): String {
    val ratio = aspectRatioToFloat(aspectRatio) // width / height
    return if (knownIsWidth) {
        val height = (known / ratio).roundToInt().coerceAtLeast(1)
        "$known*$height"
    } else {
        val width = (known * ratio).roundToInt().coerceAtLeast(1)
        "$width*$known"
    }
}

/**
 * Sound-effect generation modes a user can pick (see [GenerationSetup.sfxModel]); both
 * "fun-audiogen*" modes are served by the same DashScope audio-generation model:
 * - "wan": a short WAN video is generated and its audio track extracted (legacy pipeline).
 * - "fun-audiogen": synthesizes the audio directly from the text prompt.
 * - "fun-audiogen-vd": video-driven, scores a WAN source video with audio matching its visuals.
 */
val SUPPORTED_SFX_MODELS: List<String> = listOf("wan", "fun-audiogen", "fun-audiogen-vd")

/**
 * Vocal gender options offered for Fun-Music generation (see [GenerationSetup.gender]). These map
 * directly to the Fun-Music API's `gender` input; an empty selection lets the model decide and
 * sends no `gender` at all.
 */
val SUPPORTED_MUSIC_GENDERS: List<String> = listOf("female", "male")

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

/** The natural end of a clip on the timeline: where its media/text stops (seconds). */
fun Clip.timelineEnd(): Float = timelineStart + (trimOut - trimIn)

/**
 * The largest gap (seconds) between two consecutive visual clips on the same track that the
 * renderer and the live preview will "bridge" — holding the earlier clip's LAST frame until the
 * next clip begins — instead of letting the black stage show through.
 *
 * Gaps this small are almost always unintended sub-frame slivers: two clips the user placed
 * back-to-back end up a hair apart because of float drift or a free-drag that didn't quite snap.
 * When such a sliver happens to straddle a render/preview frame sample, that one frame finds NO
 * clip under it and falls through to the black base — the "black frame between videos" bug. Bridging
 * these slivers removes the flash; anything larger is treated as a deliberate gap and stays black.
 */
const val MAX_BRIDGE_GAP_SECONDS: Float = 0.05f

/**
 * The time (seconds on the timeline) up to which [clip] should keep being shown, given the other
 * [trackClips] on its own track. This is normally the clip's own [timelineEnd], but when the next
 * clip on the track starts just a sliver later (a gap of at most [MAX_BRIDGE_GAP_SECONDS]) the end
 * is stretched to that next clip's start, so the previous frame is held across the sliver rather
 * than the black stage peeking through for a single frame. Shared by [app.moviestudio.ui] preview
 * and the FFmpeg export so the two bridge identically.
 *
 * Only clips that start at or after this clip's natural end are considered (an overlapping later
 * clip never shortens it), and a gap larger than [MAX_BRIDGE_GAP_SECONDS] — a deliberate gap — is
 * left untouched.
 */
fun bridgedClipEnd(clip: Clip, trackClips: List<Clip>): Float {
    val naturalEnd = clip.timelineEnd()
    val nextStart = trackClips.asSequence()
        .filter { it.id != clip.id }
        .map { it.timelineStart }
        .filter { it >= naturalEnd }
        .minOrNull() ?: return naturalEnd
    val gap = nextStart - naturalEnd
    return if (gap in 0f..MAX_BRIDGE_GAP_SECONDS) nextStart else naturalEnd
}
