package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.GenerationSetup
import app.moviestudio.MusicSequence
import app.moviestudio.NetworkService
import app.moviestudio.SEQUENCER_MAX_PITCH
import app.moviestudio.SEQUENCER_MAX_TEMPO_BPM
import app.moviestudio.SEQUENCER_MIN_TEMPO_BPM
import app.moviestudio.SEQUENCER_STEPS_PER_MEASURE
import app.moviestudio.SEQUENCER_VISIBLE_OCTAVES
import app.moviestudio.SUPPORTED_IMAGE_SIZES
import app.moviestudio.SUPPORTED_MUSIC_GENDERS
import app.moviestudio.SUPPORTED_SFX_MODELS
import app.moviestudio.SUPPORTED_VIDEO_SIZES
import app.moviestudio.SequencerNote
import app.moviestudio.UploadedDeviceFile
import app.moviestudio.VOICE_INSTRUCTION_PRESETS
import app.moviestudio.VoiceClone
import app.moviestudio.cancelMicRecording
import app.moviestudio.closestSizeForAspect
import app.moviestudio.isPitchInScale
import app.moviestudio.playSequencerTone
import app.moviestudio.sequencerRowFrequency
import app.moviestudio.startMicRecording
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val setupJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The "generate video / image" dialog (simple yet powerful): a prompt plus optional inputs — a
 * start image, reference images, saved characters and scenes. The WAN 2.7 model is selected
 * predictably from what the user attaches (T2V / I2V / R2V) and shown live. The full setup is
 * stored on the generated asset so it can be retried or tweaked later.
 */
@Composable
fun GenerateMediaDialog(
    viewModel: AppViewModel,
    initialAsset: Asset?,
    onDismiss: () -> Unit
) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        } ?: GenerationSetup(kind = "video", prompt = initialAsset?.description ?: "")
    }

    // When editing an existing media asset, start in the mode matching its type (image edits start
    // on "Image", video edits on "Video") and pre-select that very asset as the base media, so the
    // edit repaints the thing the user opened.
    val editingImage = initialAsset?.type == AssetType.IMAGE && initialAsset.ossUrl.isNotBlank()
    val editingVideo = initialAsset?.type == AssetType.VIDEO && initialAsset.ossUrl.isNotBlank()

    var kind by remember {
        mutableStateOf(
            when {
                editingImage -> "image"
                editingVideo -> "video"
                initialSetup.kind == "image" -> "image"
                else -> "video"
            }
        )
    }
    var prompt by remember { mutableStateOf(initialSetup.prompt) }
    var negativePrompt by remember { mutableStateOf(initialSetup.negativePrompt) }
    var imageUrl by remember {
        mutableStateOf(if (editingImage) initialAsset!!.ossUrl else initialSetup.imageUrl)
    }
    var videoUrl by remember {
        mutableStateOf(if (editingVideo) initialAsset!!.ossUrl else initialSetup.videoUrl)
    }
    var characterIds by remember { mutableStateOf(initialSetup.characterIds) }
    var sceneIds by remember { mutableStateOf(initialSetup.sceneIds) }
    var referenceImages by remember { mutableStateOf(initialSetup.referenceImages) }
    var duration by remember { mutableStateOf(initialSetup.durationSeconds.coerceIn(2.0, 15.0)) }
    // New generations default to the size whose aspect is closest to the movie's aspect ratio;
    // regenerations keep the size they were originally made with.
    val movieAspect = viewModel.currentMovie?.aspectRatio ?: "16:9"
    var resolution by remember {
        mutableStateOf(
            if (initialAsset != null && initialSetup.resolution.isNotBlank()) {
                initialSetup.resolution
            } else {
                val sizes = if (initialSetup.kind == "image") SUPPORTED_IMAGE_SIZES else SUPPORTED_VIDEO_SIZES
                closestSizeForAspect(sizes, movieAspect)
            }
        )
    }

    val setup = GenerationSetup(
        kind = kind,
        prompt = prompt,
        negativePrompt = negativePrompt,
        imageUrl = imageUrl,
        // The base video only applies to video generation (switches to the video-edit model).
        videoUrl = if (kind == "video") videoUrl else null,
        referenceImages = referenceImages,
        characterIds = characterIds,
        sceneIds = sceneIds,
        durationSeconds = duration,
        resolution = resolution
    )
    val modelKind = setup.resolveVideoModelKind()
    val modelLabel = when {
        kind == "image" && !imageUrl.isNullOrBlank() -> "Image edit (image-to-image)"
        kind == "image" -> "Text-to-image"
        modelKind == "videoedit" -> "WAN 2.7 Video edit"
        modelKind == "i2v" -> "WAN 2.7 I2V (image-to-video)"
        modelKind == "r2v" -> "WAN 2.7 R2V (reference-to-video)"
        else -> "WAN 2.7 T2V (text-to-video)"
    }

    // Keep the selected size valid when switching between video and image generation, again
    // preferring the size closest to the movie's aspect ratio.
    LaunchedEffect(kind) {
        val sizes = if (kind == "image") SUPPORTED_IMAGE_SIZES else SUPPORTED_VIDEO_SIZES
        if (resolution !in sizes) resolution = closestSizeForAspect(sizes, movieAspect)
    }

    val imageAssets = viewModel.libraryAssets.filter { it.type == AssetType.IMAGE && it.ossUrl.isNotBlank() }
    val videoAssets = viewModel.libraryAssets.filter { it.type == AssetType.VIDEO && it.ossUrl.isNotBlank() }

    // Placeholder assets (description only, no media yet) generate rather than edit in place.
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = if (regenerating) "Edit" else "Generate video or image",
        onDismiss = onDismiss,
        width = 620.dp
    ) {
        // Kind switch
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("video" to "🎬 Video", "image" to "🖼️ Image").forEach { (value, label) ->
                if (kind == value) {
                    PillButton(label, compact = true) { }
                } else {
                    GhostPillButton(label, compact = true) { kind = value }
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    "Model: $modelLabel",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.height(10.dp))

        StudioTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Prompt",
            placeholder = "A sunny meadow full of wildflowers, butterflies drifting by...",
            minLines = 2,
            maxLines = 4,
            showAiButton = true
        )
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = negativePrompt,
            onValueChange = { negativePrompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Negative prompt (optional)",
            placeholder = "blurry, low quality, watermark...",
            singleLine = true
        )

        if (kind == "image") {
            // Image-to-image editing: pick any library image as the base and the prompt repaints
            // it (repose a character, restyle a shot, swap the background...).
            SectionLabel("Base image (optional — switches to image editing)")
            // Preview of the currently attached base image.
            imageUrl?.let { url ->
                ImageThumbnail(url, size = 96.dp) { imageUrl = null }
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (imageUrl == null) {
                    PillButton("None", compact = true) { }
                } else {
                    GhostPillButton("None", compact = true) { imageUrl = null }
                }
                imageAssets.take(12).forEach { image ->
                    val selected = imageUrl == image.ossUrl
                    val label = "🖼 " + (image.description ?: "image").take(18)
                    if (selected) {
                        PillButton(label, compact = true) { imageUrl = null }
                    } else {
                        GhostPillButton(label, compact = true) { imageUrl = image.ossUrl }
                    }
                }
                if (imageAssets.isEmpty()) {
                    Text(
                        "No images in the library yet — generate or upload one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (kind == "video") {
            // Base video: attach a source clip to edit it with the wan2.7-videoedit model.
            SectionLabel("Base video (switches to video editing)")
            // Preview of the currently attached base video.
            videoUrl?.let { url ->
                VideoPreview(url, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (videoUrl == null) {
                    PillButton("None", compact = true) { }
                } else {
                    GhostPillButton("None", compact = true) { videoUrl = null }
                }
                videoAssets.take(12).forEach { video ->
                    val selected = videoUrl == video.ossUrl
                    val label = "🎬 " + (video.description ?: "video").take(18)
                    if (selected) {
                        PillButton(label, compact = true) { videoUrl = null }
                    } else {
                        GhostPillButton(label, compact = true) { videoUrl = video.ossUrl }
                    }
                }
                if (videoAssets.isEmpty()) {
                    Text(
                        "No videos in the library yet — generate or upload one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("Start image (switches to I2V)")
            // Preview of the currently attached start image.
            imageUrl?.let { url ->
                ImageThumbnail(url, size = 96.dp) { imageUrl = null }
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (imageUrl == null) {
                    PillButton("None", compact = true) { }
                } else {
                    GhostPillButton("None", compact = true) { imageUrl = null }
                }
                imageAssets.take(12).forEach { image ->
                    val selected = imageUrl == image.ossUrl
                    val label = "🖼 " + (image.description ?: "image").take(18)
                    if (selected) {
                        PillButton(label, compact = true) { imageUrl = null }
                    } else {
                        GhostPillButton(label, compact = true) { imageUrl = image.ossUrl }
                    }
                }
            }

            SectionLabel("Characters & scenes (switches to R2V)")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                viewModel.characters.forEach { character ->
                    val selected = character.id in characterIds
                    val label = "👤 ${character.name}"
                    if (selected) {
                        PillButton(label, compact = true) { characterIds = characterIds - character.id }
                    } else {
                        GhostPillButton(label, compact = true) { characterIds = characterIds + character.id }
                    }
                }
                viewModel.scenes.forEach { scene ->
                    val selected = scene.id in sceneIds
                    val label = "🏞️ ${scene.name}"
                    if (selected) {
                        PillButton(label, compact = true) { sceneIds = sceneIds - scene.id }
                    } else {
                        GhostPillButton(label, compact = true) { sceneIds = sceneIds + scene.id }
                    }
                }
                if (viewModel.characters.isEmpty() && viewModel.scenes.isEmpty()) {
                    Text(
                        "No saved characters or scenes yet — create them in the library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionLabel("Extra reference images (R2V)")
            // Preview of the attached reference images.
            if (referenceImages.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    referenceImages.forEach { url ->
                        ImageThumbnail(url, size = 64.dp) { referenceImages = referenceImages - url }
                    }
                }
            }
            var uploadingReference by remember { mutableStateOf(false) }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom reference images: upload any picture straight from the device.
                GhostPillButton(
                    if (uploadingReference) "Uploading..." else "📤 Upload custom",
                    compact = true,
                    enabled = !uploadingReference
                ) {
                    uploadingReference = true
                    viewModel.uploadReferenceImage { url ->
                        uploadingReference = false
                        if (url != null) referenceImages = referenceImages + url
                    }
                }
                val visibleImages = imageAssets.take(12)
                visibleImages.forEach { image ->
                    val selected = image.ossUrl in referenceImages
                    val label = "📎 " + (image.description ?: "image").take(16)
                    if (selected) {
                        PillButton(label, compact = true) { referenceImages = referenceImages - image.ossUrl }
                    } else {
                        GhostPillButton(label, compact = true) { referenceImages = referenceImages + image.ossUrl }
                    }
                }
                // Selected references that are not among the shown library images (e.g. fresh
                // custom uploads) stay visible and removable.
                referenceImages.filter { url -> visibleImages.none { it.ossUrl == url } }.forEach { url ->
                    RemovableChip("📎 " + url.substringAfterLast('/').take(16)) {
                        referenceImages = referenceImages - url
                    }
                }
                if (imageAssets.isEmpty()) {
                    Text(
                        "No images in the library yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live progress while a custom reference image uploads from the device.
            viewModel.uploadState?.let { upload ->
                Spacer(Modifier.height(6.dp))
                UploadProgressBar(upload)
            }

            Spacer(Modifier.height(6.dp))
            LabeledSlider(
                label = "Duration",
                value = duration.toFloat(),
                valueRange = 2f..15f,
                valueText = "${duration.roundToInt()}s",
                onValueChange = { duration = it.toDouble() }
            )
        }

        DropdownSelector(
            label = "Resolution",
            options = if (kind == "image") SUPPORTED_IMAGE_SIZES else SUPPORTED_VIDEO_SIZES,
            selected = resolution,
            display = { it }
        ) { resolution = it }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("✨ Generate", enabled = prompt.isNotBlank()) {
                viewModel.generateMedia(setup, assetId = initialAsset?.id)
                onDismiss()
            }
        }
    }
}

/**
 * AI music generation via Fun-Music: a complete lyric editor (with its own AI-generate button),
 * a theme field (with its own AI-generate button) and an instrumental switch. Both AI-generate
 * buttons open the reusable [AiPromptDialog], where the prompt can be reviewed and edited before
 * it's sent and the result refined with chat-style follow-ups. With an [initialAsset], the
 * dialog opens pre-filled from that asset's stored generation setup and regenerates it in place
 * (pushing the old media onto its history).
 */
@Composable
fun GenerateMusicDialog(viewModel: AppViewModel, initialAsset: Asset? = null, onDismiss: () -> Unit) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        }
    }
    var theme by remember { mutableStateOf(initialSetup?.theme ?: initialAsset?.description ?: "") }
    var lyric by remember { mutableStateOf(initialSetup?.lyric ?: "") }
    var instrumental by remember { mutableStateOf(initialSetup?.instrumental ?: false) }
    // Preferred vocal gender ("female"/"male"); blank lets Fun-Music pick. Only relevant with vocals.
    var gender by remember { mutableStateOf(initialSetup?.gender ?: "") }
    // Which AI chat dialog is open: "theme", "lyrics" or none. Both ✨ buttons open the
    // reusable [AiPromptDialog] so the prompt can be reviewed/edited and refined with follow-ups.
    var aiAssist by remember { mutableStateOf<String?>(null) }
    val movieTitle = viewModel.currentMovie?.title ?: ""

    // Placeholder assets (description only, no media yet) generate rather than regenerate.
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = if (regenerating) "Regenerate music" else "Generate music",
        onDismiss = onDismiss,
        width = 560.dp
    ) {
        StudioTextField(
            value = theme,
            onValueChange = { theme = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Theme",
            placeholder = "Uplifting electro-pop with soaring strings...",
            singleLine = true,
            trailingIcon = {
                RoundIconButton("✨", contentDescription = "Generate theme", size = 28.dp) {
                    aiAssist = "theme"
                }
            }
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = instrumental, onCheckedChange = { instrumental = it })
            Spacer(Modifier.width(8.dp))
            Text(
                "Instrumental (no vocals)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.height(10.dp))

        // Vocal gender only applies when the track has vocals.
        if (!instrumental) {
            MusicGenderToggle(selected = gender) { gender = it }
            Spacer(Modifier.height(10.dp))
        }

        StudioTextField(
            value = lyric,
            onValueChange = { lyric = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Lyrics",
            placeholder = "[verse]\nCity lights are calling out my name...",
            minLines = 5,
            maxLines = 10,
            enabled = !instrumental,
            trailingIcon = {
                RoundIconButton("✨", contentDescription = "Generate lyrics", size = 28.dp, enabled = !instrumental) {
                    aiAssist = "lyrics"
                }
            }
        )

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                if (regenerating) "🔄 Regenerate music" else "🎵 Generate music",
                enabled = theme.isNotBlank() || lyric.isNotBlank()
            ) {
                viewModel.generateMedia(
                    GenerationSetup(
                        kind = "music",
                        theme = theme,
                        lyric = lyric,
                        instrumental = instrumental,
                        gender = if (instrumental) "" else gender
                    ),
                    assetId = initialAsset?.id
                )
                onDismiss()
            }
        }
    }

    // AI chat with prompt review + follow-up refinement (chat-style), on top of this dialog.
    when (aiAssist) {
        "theme" -> AiPromptDialog(
            title = "Generate theme",
            description = "Review and edit the prompt before it's sent, then refine the " +
                "suggestion with follow-ups until it fits your movie.",
            initialPrompt = theme.ifBlank { "A soundtrack theme for the movie \"$movieTitle\"" },
            promptLabel = "Prompt",
            promptPlaceholder = "Describe the mood, genre or scene the music should match...",
            generateLabel = "✨ Generate theme",
            acceptLabel = "✅ Use theme",
            generate = { messages -> NetworkService.generateTheme(messages, movieTitle) },
            onAccept = { theme = it; aiAssist = null },
            onDismiss = { aiAssist = null }
        )
        "lyrics" -> AiPromptDialog(
            title = "Generate lyrics",
            description = "Review and edit the prompt before it's sent, then refine the lyrics " +
                "with follow-ups until they sing.",
            initialPrompt = theme.ifBlank { lyric }
                .ifBlank { "An original song for the movie \"$movieTitle\"" },
            promptLabel = "Prompt",
            promptPlaceholder = "What should the song be about?",
            generateLabel = "✨ Generate lyrics",
            acceptLabel = "✅ Use lyrics",
            generate = { messages -> NetworkService.generateLyrics(messages, movieTitle) },
            onAccept = { lyric = it; aiAssist = null },
            onDismiss = { aiAssist = null }
        )
    }
}

/**
 * Female/Male vocal-gender picker for [GenerateMusicDialog]. Two pills mapped to
 * [SUPPORTED_MUSIC_GENDERS]; tapping the active pill clears the choice (blank = let Fun-Music
 * decide). Each pill is clipped before its clickable so the hover/press highlight follows the
 * rounded corners (project guideline).
 */
@Composable
private fun MusicGenderToggle(selected: String, onSelect: (String) -> Unit) {
    Column {
        Text(
            "Vocal gender",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SUPPORTED_MUSIC_GENDERS.forEach { option ->
                val active = selected == option
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50)) // clip BEFORE clickable so the hover pill is rounded
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .clickable { onSelect(if (active) "" else option) }
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    Text(
                        option.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The (column, bottom-up row) grid cell under a pointer [offset]. Column 0 is the leftmost step of
 * the visible measure; row 0 is the lowest visible pitch.
 */
private fun sequencerGridCell(
    offset: Offset,
    widthPx: Int,
    heightPx: Int,
    cols: Int,
    rows: Int
): Pair<Int, Int> {
    val col = (offset.x / (widthPx / cols.toFloat())).toInt().coerceIn(0, cols - 1)
    val visualRow = (offset.y / (heightPx / rows.toFloat())).toInt().coerceIn(0, rows - 1)
    return col to (rows - 1 - visualRow)
}

/** A compact glyph identifying a note's instrument, drawn on the note's start cell. */
private fun instrumentGlyph(waveform: String): String = when (waveform) {
    "square" -> "\u25FC"   // ◼
    "saw" -> "\u25E4"      // ◤
    "triangle" -> "\u25B3" // △
    "sample" -> "\uD83D\uDD0A" // 🔊
    else -> "\u223F"       // ∿ sine
}

/**
 * The mini music sequencer: a piano-roll grid synthesized server-side into a WAV music asset.
 * The pattern is stored on the asset so it can be reopened and edited later.
 *
 * Interactions: click a cell to place/remove a note, drag horizontally to create a longer note or
 * to extend/contract an existing one. Every note carries its own instrument (shown by a glyph);
 * the instrument picker below sets the instrument used for newly placed notes. The grid shows one
 * measure and a two-octave window at a time — arrows move between measures and octaves, ± add or
 * remove measures, the key menu picks major/minor and "Show all pitches" reveals off-key rows.
 */
@Composable
fun SequencerDialog(viewModel: AppViewModel, existingAsset: Asset?, onDismiss: () -> Unit) {
    val initial = remember(existingAsset) {
        existingAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(MusicSequence.serializer(), it) }.getOrNull()
        } ?: MusicSequence()
    }
    val stepsPerMeasure = SEQUENCER_STEPS_PER_MEASURE

    var name by remember { mutableStateOf(initial.name) }
    var tempo by remember {
        mutableStateOf(initial.tempoBpm.coerceIn(SEQUENCER_MIN_TEMPO_BPM, SEQUENCER_MAX_TEMPO_BPM))
    }
    var loops by remember { mutableStateOf(initial.loops) }
    // The "current instrument": stamped onto newly placed notes (each note keeps its own) and
    // kept as the sequence-level fallback for patterns saved before per-note instruments.
    var waveform by remember { mutableStateOf(initial.waveform) }
    var sampleUrl by remember { mutableStateOf(initial.sampleUrl) }
    var sampleName by remember { mutableStateOf(initial.sampleName) }
    var notes by remember {
        mutableStateOf(initial.notes.map { it.copy(lengthSteps = it.lengthSteps.coerceAtLeast(1)) })
    }
    // Musical key the grid highlights, and how many 16-step measures the pattern spans.
    var scale by remember { mutableStateOf(initial.scale) }
    var measures by remember { mutableStateOf(initial.measures.coerceIn(1, 32)) }
    // Which measure is on screen, and the lowest visible octave (octave arrows shift it).
    var currentMeasure by remember { mutableStateOf(0) }
    var octaveBase by remember { mutableStateOf(24) } // start around C4
    // Reveal every chromatic pitch (off-key rows drawn dimmer), not just the in-key rows.
    var showAll by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Generate a brand-new sound effect without leaving the sequencer; once its job finishes it
    // shows up among the sample instruments below (like every library sound effect).
    var showSfxDialog by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(-1) }
    // The note currently being created/resized by a horizontal drag on the grid.
    var resizingNote by remember { mutableStateOf<SequencerNote?>(null) }

    // Total step count across all measures, plus the pitches shown in the current octave window
    // (bottom-to-top, low-to-high). "Show all" lists every semitone; otherwise only in-key rows.
    val totalSteps = measures * stepsPerMeasure
    val visiblePitches = (octaveBase until octaveBase + SEQUENCER_VISIBLE_OCTAVES * 12)
        .filter { showAll || isPitchInScale(it, scale) }
    val rows = visiblePitches.size
    val maxOctaveBase = (SEQUENCER_MAX_PITCH - SEQUENCER_VISIBLE_OCTAVES * 12).coerceAtLeast(0)

    // Sounds one note through the platform audio engine, honoring its own instrument (falling
    // back to the current instrument for notes saved before per-note instruments).
    fun playNote(note: SequencerNote, durationSeconds: Double) {
        playSequencerTone(
            waveform = note.waveform ?: waveform,
            frequencyHz = sequencerRowFrequency(note.pitch),
            durationSeconds = durationSeconds,
            volume = 0.35,
            sampleUrl = note.sampleUrl ?: sampleUrl
        )
    }

    /** The note whose span covers the given cell, if any. */
    fun noteCovering(step: Int, pitch: Int): SequencerNote? =
        notes.lastOrNull { it.pitch == pitch && it.covers(step) }

    /** Plays every note sounding at [step] together (chord preview). */
    fun playColumn(step: Int) {
        notes.filter { it.covers(step) }.forEach { playNote(it, 0.3) }
    }

    /** Places a new note at the cell with the current instrument and previews the whole column. */
    fun placeNote(step: Int, pitch: Int): SequencerNote {
        val note = SequencerNote(
            step = step,
            pitch = pitch,
            lengthSteps = 1,
            waveform = waveform,
            sampleUrl = if (waveform == "sample") sampleUrl else null,
            sampleName = if (waveform == "sample") sampleName else null
        )
        notes = notes + note
        // Audible feedback: the new note plays along all other notes at this position.
        playColumn(step)
        return note
    }

    // Looping playback: the pattern repeats until stopped, sounding each step's notes through
    // the platform audio engine while the playing column is highlighted in pink.
    LaunchedEffect(playing) {
        if (!playing) {
            currentStep = -1
            return@LaunchedEffect
        }
        var step = 0
        while (playing) {
            currentStep = step
            currentMeasure = step / stepsPerMeasure // follow playback across measures
            val stepSeconds = 60.0 / tempo.coerceIn(SEQUENCER_MIN_TEMPO_BPM, SEQUENCER_MAX_TEMPO_BPM) / 4.0
            notes.filter { it.step == step }.forEach { note ->
                // Held notes ring for their full dragged length.
                playNote(note, stepSeconds * (note.lengthSteps.coerceAtLeast(1) + 0.9))
            }
            delay((stepSeconds * 1000).toLong())
            step = (step + 1) % totalSteps
        }
    }

    StudioDialog(title = "Music sequencer", onDismiss = onDismiss, width = 640.dp) {
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))

        // The grid: bottom row = lowest note. Rendered top-down. A single pointer surface
        // handles both taps (toggle a note) and horizontal drags (create long notes or
        // extend/contract existing ones).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
                .padding(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Tap: toggle the note under the pointer. The gesture blocks restart whenever
                    // the visible pitch window / measure changes so they never read a stale window.
                    .pointerInput(scale, showAll, octaveBase, currentMeasure) {
                        detectTapGestures { offset ->
                            val (col, rowIndex) = sequencerGridCell(offset, size.width, size.height, stepsPerMeasure, rows)
                            val pitch = visiblePitches.getOrNull(rowIndex) ?: return@detectTapGestures
                            val step = currentMeasure * stepsPerMeasure + col
                            val existing = noteCovering(step, pitch)
                            if (existing != null) {
                                notes = notes - existing
                            } else {
                                placeNote(step, pitch)
                            }
                        }
                    }
                    // Horizontal drag: place a note and stretch it, or resize an existing note.
                    .pointerInput(scale, showAll, octaveBase, currentMeasure) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                val (col, rowIndex) = sequencerGridCell(offset, size.width, size.height, stepsPerMeasure, rows)
                                val pitch = visiblePitches.getOrNull(rowIndex)
                                if (pitch != null) {
                                    val step = currentMeasure * stepsPerMeasure + col
                                    resizingNote = noteCovering(step, pitch) ?: placeNote(step, pitch)
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val anchor = resizingNote ?: return@detectDragGestures
                                val (col, _) = sequencerGridCell(change.position, size.width, size.height, stepsPerMeasure, rows)
                                val step = currentMeasure * stepsPerMeasure + col
                                val length = (step - anchor.step + 1).coerceIn(1, totalSteps - anchor.step)
                                if (length != anchor.lengthSteps) {
                                    val updated = anchor.copy(lengthSteps = length)
                                    notes = notes.map {
                                        if (it.step == anchor.step && it.pitch == anchor.pitch) updated else it
                                    }
                                    resizingNote = updated
                                }
                            },
                            onDragEnd = { resizingNote = null },
                            onDragCancel = { resizingNote = null }
                        )
                    }
            ) {
                Column {
                    for (rowIndex in (rows - 1) downTo 0) {
                        val pitch = visiblePitches[rowIndex]
                        val inScale = isPitchInScale(pitch, scale)
                        Row {
                            for (col in 0 until stepsPerMeasure) {
                                val step = currentMeasure * stepsPerMeasure + col
                                val covering = noteCovering(step, pitch)
                                val active = covering != null
                                val isStart = covering?.step == step
                                val isEnd = covering != null && !covering.covers(step + 1)
                                val isCurrent = playing && step == currentStep
                                // In-key rows use the normal beat shading; off-key rows (only shown
                                // via "Show all pitches") are tinted so the scale still stands out.
                                val beatShade = when {
                                    !inScale && (col / 4) % 2 == 0 -> Color(0xFF2A2130)
                                    !inScale -> Color(0xFF241B29)
                                    (col / 4) % 2 == 0 -> Color(0xFF232030)
                                    else -> Color(0xFF1D1A27)
                                }
                                // The playing column lights up pink while the loop passes over it.
                                val cellColor = when {
                                    active && isCurrent -> Color(0xFFFF5A9E)
                                    active && inScale -> Color(0xFF8F7BFF)
                                    active -> Color(0xFFC08BFF) // off-key note
                                    isCurrent -> Color(0xFF3A2136)
                                    else -> beatShade
                                }
                                // Held notes render as one continuous bar across their steps.
                                val shape = when {
                                    !active || (isStart && isEnd) -> RoundedCornerShape(4.dp)
                                    isStart -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                                    isEnd -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                                    else -> RoundedCornerShape(0.dp)
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(16.dp)
                                        .padding(
                                            start = if (active && !isStart) 0.dp else 1.dp,
                                            end = if (active && !isEnd) 0.dp else 1.dp,
                                            top = 1.dp,
                                            bottom = 1.dp
                                        )
                                        .clip(shape)
                                        .background(cellColor),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // A small glyph marks the note's instrument at its start cell.
                                    if (active && isStart) {
                                        Text(
                                            instrumentGlyph(covering.waveform ?: waveform),
                                            fontSize = 9.sp,
                                            color = Color(0xFF1B1620)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Click to place a note, drag sideways to hold it. Use ± to add or remove measures; " +
                "arrows move between measures and octaves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))

        // Measure navigation / add-remove, and octave-window shifting.
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton("\u25C0", contentDescription = "Previous measure", enabled = currentMeasure > 0) {
                if (currentMeasure > 0) currentMeasure--
            }
            Text(
                "Measure ${currentMeasure + 1}/$measures",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            RoundIconButton("\u25B6", contentDescription = "Next measure", enabled = currentMeasure < measures - 1) {
                if (currentMeasure < measures - 1) currentMeasure++
            }
            Spacer(Modifier.width(10.dp))
            RoundIconButton("\u2796", contentDescription = "Remove measure", enabled = measures > 1) {
                if (measures > 1) {
                    measures--
                    if (currentMeasure > measures - 1) currentMeasure = measures - 1
                    notes = notes.filter { it.step < measures * stepsPerMeasure }
                }
            }
            RoundIconButton("\u2795", contentDescription = "Add measure", enabled = measures < 32) {
                if (measures < 32) {
                    measures++
                    currentMeasure = measures - 1
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Octave",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp)
            )
            RoundIconButton("\u25BC", contentDescription = "Lower octave", enabled = octaveBase > 0) {
                octaveBase = (octaveBase - 12).coerceIn(0, maxOctaveBase)
            }
            RoundIconButton("\u25B2", contentDescription = "Higher octave", enabled = octaveBase < maxOctaveBase) {
                octaveBase = (octaveBase + 12).coerceIn(0, maxOctaveBase)
            }
        }
        Spacer(Modifier.height(6.dp))
        // Key selector and reveal-all-pitches toggle.
        Row(verticalAlignment = Alignment.CenterVertically) {
            DropdownSelector(
                label = null,
                options = listOf("major", "minor"),
                selected = scale,
                display = { it.replaceFirstChar { c -> c.uppercase() } },
                modifier = Modifier.width(150.dp)
            ) { scale = it }
            Spacer(Modifier.weight(1f))
            Text(
                "Show all pitches",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Switch(checked = showAll, onCheckedChange = { showAll = it })
        }
        Spacer(Modifier.height(8.dp))

        Row {
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = "Tempo",
                    value = tempo.toFloat(),
                    valueRange = SEQUENCER_MIN_TEMPO_BPM.toFloat()..SEQUENCER_MAX_TEMPO_BPM.toFloat(),
                    valueText = "$tempo BPM",
                    onValueChange = { tempo = it.roundToInt() }
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = "Loops",
                    value = loops.toFloat(),
                    valueRange = 1f..16f,
                    valueText = "×$loops",
                    onValueChange = { loops = it.roundToInt() }
                )
            }
        }
        // Instrument for newly placed notes: the four classic waveforms plus every sound effect
        // in the library, played as a pitch-shifted sample. Each note remembers the instrument
        // it was placed with, so a single pattern can mix instruments freely.
        val sfxAssets = viewModel.libraryAssets.filter { it.type == AssetType.AUDIO && it.ossUrl.isNotBlank() }
        val instrumentOptions: List<Pair<String, Asset?>> =
            listOf("sine", "square", "saw", "triangle").map { it to null } + sfxAssets.map { "sample" to it }
        val selectedInstrument = instrumentOptions.firstOrNull { (wf, asset) ->
            if (waveform == "sample") asset?.ossUrl == sampleUrl else wf == waveform && asset == null
        } ?: instrumentOptions.first()
        Row(verticalAlignment = Alignment.Bottom) {
            DropdownSelector(
                label = "Instrument (for new notes)",
                options = instrumentOptions,
                selected = selectedInstrument,
                display = { (wf, asset) ->
                    if (asset != null) "💥 " + (asset.description ?: asset.aiPrompt ?: "Sound effect").take(28)
                    else wf.replaceFirstChar { c -> c.uppercase() }
                },
                modifier = Modifier.weight(1f)
            ) { (wf, asset) ->
                if (asset != null) {
                    waveform = "sample"
                    sampleUrl = asset.ossUrl
                    sampleName = (asset.description ?: asset.aiPrompt ?: "Sound effect").take(40)
                } else {
                    waveform = wf
                    sampleUrl = null
                    sampleName = null
                }
            }
            Spacer(Modifier.width(8.dp))
            GhostPillButton("💥 Generate sound effect", compact = true) { showSfxDialog = true }
        }

        DialogActions {
            PillButton(
                if (playing) "⏹ Stop" else "▶ Play",
                compact = true,
                enabled = notes.isNotEmpty() || playing
            ) { playing = !playing }
            ActionSpacer()
            GhostPillButton("Clear", compact = true) { notes = emptyList() }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(if (saving) "Rendering..." else "💾 Save as music", enabled = notes.isNotEmpty() && !saving) {
                saving = true
                playing = false
                val sequence = MusicSequence(
                    name = name.ifBlank { "Sequence" },
                    tempoBpm = tempo,
                    steps = totalSteps,
                    loops = loops,
                    waveform = waveform,
                    sampleUrl = sampleUrl,
                    sampleName = sampleName,
                    scale = scale,
                    notes = notes.sortedWith(compareBy({ it.step }, { it.pitch }))
                )
                viewModel.createSequenceAsset(sequence) { onDismiss() }
            }
        }
    }

    if (showSfxDialog) {
        SoundEffectDialog(viewModel) { showSfxDialog = false }
    }
}

/** User-facing label for a sound-effect generation mode (see [SUPPORTED_SFX_MODELS]). */
private fun sfxModelLabel(model: String): String = when (model) {
    "fun-audiogen" -> "Direct (text-to-audio)"
    "fun-audiogen-vd" -> "Video-driven (audio scoring)"
    else -> "WAN 2.7 (video + audio extraction)"
}

/**
 * Sound-effect generation with a selectable mode: direct synthesizes audio straight from the
 * prompt, video-driven scores a generated WAN source video, and the classic WAN 2.7 pipeline
 * renders a short video whose audio track the server extracts into the library. With an
 * [initialAsset], the dialog opens pre-filled from that asset's stored generation setup and
 * regenerates it in place (pushing the old media onto its history).
 */
@Composable
fun SoundEffectDialog(viewModel: AppViewModel, initialAsset: Asset? = null, onDismiss: () -> Unit) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        }
    }
    var prompt by remember { mutableStateOf(initialSetup?.prompt ?: initialAsset?.description ?: "") }
    var duration by remember { mutableStateOf(initialSetup?.durationSeconds?.coerceIn(2.0, 12.0) ?: 5.0) }
    var sfxModel by remember {
        mutableStateOf(initialSetup?.sfxModel?.takeIf { it in SUPPORTED_SFX_MODELS } ?: SUPPORTED_SFX_MODELS.first())
    }

    // Placeholder assets (description only, no media yet) generate rather than regenerate.
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = if (regenerating) "Regenerate sound effect" else "Generate sound effect",
        onDismiss = onDismiss,
        width = 500.dp
    ) {
        Text(
            when (sfxModel) {
                "fun-audiogen" ->
                    "The sound is synthesized directly from your description and drops " +
                        "into the sound-effects library, ready to clip."
                "fun-audiogen-vd" ->
                    "WAN 2.7 generates a short video for your prompt, then the audio is scored " +
                        "to match the visuals — the result lands in the " +
                        "sound-effects library, ready to clip."
                else ->
                    "WAN 2.7 generates a short video for your prompt; its audio track is extracted " +
                        "into the sound-effects library, ready to clip."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Sound description",
            placeholder = "Thunder rolling over a canyon, heavy rain...",
            minLines = 2,
            showAiButton = true
        )
        Spacer(Modifier.height(8.dp))
        DropdownSelector(
            label = "Model",
            options = SUPPORTED_SFX_MODELS,
            selected = sfxModel,
            display = { sfxModelLabel(it) }
        ) { sfxModel = it }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            label = "Duration",
            value = duration.toFloat(),
            valueRange = 2f..12f,
            valueText = "${duration.roundToInt()}s",
            onValueChange = { duration = it.toDouble() }
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                if (regenerating) "🔄 Regenerate" else "💥 Generate",
                enabled = prompt.isNotBlank()
            ) {
                viewModel.generateMedia(
                    GenerationSetup(kind = "sfx", prompt = prompt, durationSeconds = duration, sfxModel = sfxModel),
                    assetId = initialAsset?.id
                )
                onDismiss()
            }
        }
    }
}

/**
 * Text-to-speech: voice selector offering every Qwen preset plus the user's cloned voices, a
 * "Create voice" button for Qwen voice cloning (China mainland), and optional voice instructions
 * (Qwen instruct) steering the delivery — happy, sad, excited... With an [initialAsset], the
 * dialog opens pre-filled from that asset's stored generation setup and regenerates it in place
 * (pushing the old media onto its history).
 */
@Composable
fun TtsDialog(viewModel: AppViewModel, initialAsset: Asset? = null, onDismiss: () -> Unit) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        }
    }
    var text by remember {
        mutableStateOf(
            initialSetup?.prompt?.ifBlank { null }
                ?: initialAsset?.transcript
                ?: initialAsset?.description
                ?: ""
        )
    }
    var voice by remember {
        mutableStateOf(
            initialSetup?.voice?.ifBlank { null }
                ?: initialAsset?.voice
                ?: viewModel.voiceOptions.presets.firstOrNull() ?: "Cherry"
        )
    }
    var instructions by remember { mutableStateOf(initialSetup?.instructions ?: "") }
    var showCreateVoice by remember { mutableStateOf(false) }

    val voiceEntries: List<Pair<String, String>> =
        viewModel.voiceOptions.presets.map { it to "🔊 $it" } +
            viewModel.voiceOptions.clones.map { it.qwenVoiceId to "🧬 ${it.name} (cloned)" }

    // Placeholder assets (description only, no media yet) generate rather than regenerate.
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = if (regenerating) "Regenerate speech" else "Text to speech",
        onDismiss = onDismiss,
        width = 540.dp
    ) {
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Narration text",
            placeholder = "In a world where movies make themselves...",
            minLines = 3,
            maxLines = 8,
            showAiButton = true
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            DropdownSelector(
                label = "Voice",
                options = voiceEntries,
                selected = voiceEntries.firstOrNull { it.first == voice } ?: voiceEntries.firstOrNull() ?: (voice to voice),
                display = { it.second },
                modifier = Modifier.weight(1f)
            ) { voice = it.first }
            Spacer(Modifier.width(8.dp))
            GhostPillButton("➕ Create voice", compact = true) { showCreateVoice = true }
        }
        Spacer(Modifier.height(10.dp))

        // Voice instructions (Qwen instruct): how the line should be delivered.
        StudioTextField(
            value = instructions,
            onValueChange = { instructions = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Voice instructions (optional)",
            placeholder = "How to deliver it: happy, sad, excited, whispering...",
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VOICE_INSTRUCTION_PRESETS.forEach { preset ->
                val selected = instructions.equals(preset, ignoreCase = true)
                if (selected) {
                    // Tapping the active mood again clears the instructions.
                    PillButton(preset, compact = true) { instructions = "" }
                } else {
                    GhostPillButton(preset, compact = true) { instructions = preset }
                }
            }
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                if (regenerating) "🔄 Regenerate voiceover" else "🗣️ Generate voiceover",
                enabled = text.isNotBlank()
            ) {
                viewModel.generateMedia(
                    GenerationSetup(kind = "tts", prompt = text, voice = voice, instructions = instructions.trim()),
                    assetId = initialAsset?.id
                )
                onDismiss()
            }
        }
    }

    if (showCreateVoice) {
        CreateVoiceCloneDialog(viewModel) { created ->
            showCreateVoice = false
            if (created != null) voice = created.qwenVoiceId
        }
    }
}

/**
 * Voice cloning: record a sample right in the dialog (with what-to-say instructions and a
 * target length) or pick an existing recording from the device, then enroll it as a reusable
 * voice.
 */
@Composable
fun CreateVoiceCloneDialog(viewModel: AppViewModel, onClose: (VoiceClone?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    // The finished microphone capture, kept until the user names the voice and clones it.
    var recordedSample by remember { mutableStateOf<UploadedDeviceFile?>(null) }
    var deleteTarget by remember { mutableStateOf<VoiceClone?>(null) }
    val scope = rememberCoroutineScope()

    // Elapsed-time ticker while recording.
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) {
            delay(1000)
            recordSeconds++
        }
    }
    // Abandoning the dialog mid-recording discards the capture.
    DisposableEffect(Unit) {
        onDispose { cancelMicRecording() }
    }

    StudioDialog(title = "Create voice (cloning)", onDismiss = { onClose(null) }, width = 480.dp) {
        Text(
            "Record or pick a clean voice sample. Qwen voice cloning (China mainland) enrolls it " +
                "as a reusable voice for all future narration.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Voice name",
            placeholder = "My narrator voice",
            singleLine = true
        )

        SectionLabel("Record your voice")
        Text(
            "Speak naturally in a quiet room, about 20–30 cm from the microphone. Read a couple " +
                "of sentences in your normal voice, for example:\n\n“The morning sun rose over " +
                "the valley, painting the hills in gold. I took a deep breath, smiled, and " +
                "started walking toward the river.”\n\nAim for 10–60 seconds — around 20 seconds " +
                "works best.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!recording) {
                PillButton("🔴 Record", compact = true, enabled = !working) {
                    scope.launch {
                        recordedSample = null
                        recording = startMicRecording()
                        if (!recording) {
                            viewModel.errorMessage = "Microphone unavailable or permission denied"
                        }
                    }
                }
            } else {
                // Stopping must always be possible — the sample is kept and cloning happens
                // below once the voice has a name.
                PillButton(
                    "⏹ Stop recording",
                    compact = true,
                    enabled = !working,
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    recording = false
                    working = true
                    viewModel.stopRecordingAndUpload { uploaded ->
                        working = false
                        if (uploaded == null) {
                            viewModel.errorMessage = "Recording failed — nothing was captured"
                        } else {
                            recordedSample = uploaded
                        }
                    }
                }
                GhostPillButton("Discard", compact = true) {
                    recording = false
                    cancelMicRecording()
                }
                Text(
                    "● ${formatDuration(recordSeconds.toDouble())}" +
                        if (recordSeconds < 10) " — keep going, at least ~10s" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Live progress while a recording or a picked audio sample uploads to storage.
        viewModel.uploadState?.let { upload ->
            Spacer(Modifier.height(8.dp))
            UploadProgressBar(upload)
        }
        recordedSample?.let { sample ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "✅ Sample ready (${formatDuration(sample.durationSeconds)})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                PillButton(
                    if (working) "Cloning..." else "🧬 Clone from recording",
                    compact = true,
                    enabled = name.isNotBlank() && !working
                ) {
                    working = true
                    viewModel.createVoiceCloneFromAudioUrl(name.trim(), sample.ossUrl) { clone ->
                        working = false
                        if (clone != null) onClose(clone)
                    }
                }
            }
            if (name.isBlank()) {
                Text(
                    "Give the voice a name above to enable cloning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (viewModel.voiceOptions.clones.isNotEmpty()) {
            SectionLabel("Your cloned voices")
            viewModel.voiceOptions.clones.forEach { clone ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🧬", fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        clone.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    RoundIconButton("🗑", size = 24.dp) { deleteTarget = clone }
                }
            }
        }

        DialogActions {
            GhostPillButton("Cancel") { onClose(null) }
            ActionSpacer()
            PillButton(
                if (working) "Cloning..." else "🎤 Pick sample & clone",
                enabled = name.isNotBlank() && !working && !recording
            ) {
                working = true
                viewModel.createVoiceCloneFromDevice(name.trim()) { clone ->
                    working = false
                    if (clone != null) onClose(clone)
                }
            }
        }
    }

    deleteTarget?.let { clone ->
        ConfirmDialog(
            title = "Delete cloned voice?",
            message = "\"${clone.name}\" will no longer be available for narration.",
            confirmLabel = "Delete voice",
            onConfirm = { viewModel.deleteVoiceClone(clone.id) },
            onDismiss = { deleteTarget = null }
        )
    }
}

/**
 * Records a voice clip with the device microphone and uploads it into the library as voice
 * media (with an auto-generated transcript), ready to drop on the timeline like any other sound.
 */
@Composable
fun RecordVoiceDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Elapsed-time ticker while recording.
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) {
            delay(1000)
            recordSeconds++
        }
    }
    // Abandoning the dialog mid-recording discards the capture.
    DisposableEffect(Unit) {
        onDispose { cancelMicRecording() }
    }

    StudioDialog(title = "Record voice", onDismiss = onDismiss, width = 460.dp) {
        Text(
            "Record narration or any voice line with your microphone. The recording lands in " +
                "the voice library (with an auto-generated transcript) and can be placed on the " +
                "timeline like any other sound.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name (optional)",
            placeholder = "Narration take 1",
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!recording) {
                PillButton(if (saving) "Uploading..." else "🔴 Record", compact = true, enabled = !saving) {
                    scope.launch {
                        recording = startMicRecording()
                        if (!recording) {
                            viewModel.errorMessage = "Microphone unavailable or permission denied"
                        }
                    }
                }
            } else {
                PillButton(
                    "⏹ Stop & save",
                    compact = true,
                    enabled = !saving,
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    recording = false
                    saving = true
                    viewModel.stopRecordingAndUpload { uploaded ->
                        if (uploaded == null) {
                            saving = false
                            viewModel.errorMessage = "Recording failed — nothing was captured"
                        } else {
                            viewModel.createVoiceRecordingAsset(name.trim(), uploaded) {
                                saving = false
                                onDismiss()
                            }
                        }
                    }
                }
                GhostPillButton("Discard", compact = true) {
                    recording = false
                    cancelMicRecording()
                }
                Text(
                    "● ${formatDuration(recordSeconds.toDouble())}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Live progress while the finished recording uploads to storage.
        viewModel.uploadState?.let { upload ->
            Spacer(Modifier.height(10.dp))
            UploadProgressBar(upload)
        }
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
        }
    }
}

/**
 * Records a sound effect with the device microphone and uploads it into the library as sound-fx
 * media, ready to drop on the timeline like any other sound.
 */
@Composable
fun RecordSoundEffectDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Elapsed-time ticker while recording.
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) {
            delay(1000)
            recordSeconds++
        }
    }
    // Abandoning the dialog mid-recording discards the capture.
    DisposableEffect(Unit) {
        onDispose { cancelMicRecording() }
    }

    StudioDialog(title = "Record sound effect", onDismiss = onDismiss, width = 460.dp) {
        Text(
            "Record any sound effect with your microphone. The recording lands in the sound-fx " +
                "library and can be placed on the timeline like any other sound.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name (optional)",
            placeholder = "Door slam take 1",
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!recording) {
                PillButton(if (saving) "Uploading..." else "🔴 Record", compact = true, enabled = !saving) {
                    scope.launch {
                        recording = startMicRecording()
                        if (!recording) {
                            viewModel.errorMessage = "Microphone unavailable or permission denied"
                        }
                    }
                }
            } else {
                PillButton(
                    "⏹ Stop & save",
                    compact = true,
                    enabled = !saving,
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    recording = false
                    saving = true
                    viewModel.stopRecordingAndUpload { uploaded ->
                        if (uploaded == null) {
                            saving = false
                            viewModel.errorMessage = "Recording failed — nothing was captured"
                        } else {
                            viewModel.createSoundEffectRecordingAsset(name.trim(), uploaded) {
                                saving = false
                                onDismiss()
                            }
                        }
                    }
                }
                GhostPillButton("Discard", compact = true) {
                    recording = false
                    cancelMicRecording()
                }
                Text(
                    "● ${formatDuration(recordSeconds.toDouble())}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Live progress while the finished recording uploads to storage.
        viewModel.uploadState?.let { upload ->
            Spacer(Modifier.height(10.dp))
            UploadProgressBar(upload)
        }
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
        }
    }
}

/**
 * Records music with the device microphone and uploads it into the library as music media,
 * ready to drop on the timeline like any other sound.
 */
@Composable
fun RecordMusicDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Elapsed-time ticker while recording.
    LaunchedEffect(recording) {
        recordSeconds = 0
        while (recording) {
            delay(1000)
            recordSeconds++
        }
    }
    // Abandoning the dialog mid-recording discards the capture.
    DisposableEffect(Unit) {
        onDispose { cancelMicRecording() }
    }

    StudioDialog(title = "Record music", onDismiss = onDismiss, width = 460.dp) {
        Text(
            "Record a tune or any music with your microphone. The recording lands in the music " +
                "library and can be placed on the timeline like any other sound.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name (optional)",
            placeholder = "Theme take 1",
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!recording) {
                PillButton(if (saving) "Uploading..." else "🔴 Record", compact = true, enabled = !saving) {
                    scope.launch {
                        recording = startMicRecording()
                        if (!recording) {
                            viewModel.errorMessage = "Microphone unavailable or permission denied"
                        }
                    }
                }
            } else {
                PillButton(
                    "⏹ Stop & save",
                    compact = true,
                    enabled = !saving,
                    container = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ) {
                    recording = false
                    saving = true
                    viewModel.stopRecordingAndUpload { uploaded ->
                        if (uploaded == null) {
                            saving = false
                            viewModel.errorMessage = "Recording failed — nothing was captured"
                        } else {
                            viewModel.createMusicRecordingAsset(name.trim(), uploaded) {
                                saving = false
                                onDismiss()
                            }
                        }
                    }
                }
                GhostPillButton("Discard", compact = true) {
                    recording = false
                    cancelMicRecording()
                }
                Text(
                    "● ${formatDuration(recordSeconds.toDouble())}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        // Live progress while the finished recording uploads to storage.
        viewModel.uploadState?.let { upload ->
            Spacer(Modifier.height(10.dp))
            UploadProgressBar(upload)
        }
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
        }
    }
}

/** Adds a description-only placeholder asset of any media type to the library. */
@Composable
fun DescribeAssetDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(AssetType.VIDEO) }
    var description by remember { mutableStateOf("") }

    StudioDialog(title = "Describe media", onDismiss = onDismiss, width = 500.dp) {
        Text(
            "Creates a placeholder that lives on the timeline as text until you generate its " +
                "media.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        DropdownSelector(
            label = "Media type",
            options = listOf(AssetType.VIDEO, AssetType.IMAGE, AssetType.MUSIC, AssetType.VOICE, AssetType.AUDIO),
            selected = type,
            display = { "${assetGlyph(it)} ${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}" }
        ) { type = it }
        Spacer(Modifier.height(8.dp))
        StudioTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Description",
            placeholder = "A drone shot over snowy mountain peaks at dawn...",
            minLines = 2
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Add placeholder", enabled = description.isNotBlank()) {
                viewModel.addAssetByDescription(type, description.trim())
                onDismiss()
            }
        }
    }
}
