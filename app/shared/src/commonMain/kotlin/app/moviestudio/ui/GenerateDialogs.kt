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
import app.moviestudio.DEFAULT_VOICE_ID
import app.moviestudio.GenerationSetup
import app.moviestudio.ImageModel
import app.moviestudio.MusicSequence
import app.moviestudio.NetworkService
import app.moviestudio.SEQUENCER_MAX_PITCH
import app.moviestudio.SEQUENCER_MAX_TEMPO_BPM
import app.moviestudio.SEQUENCER_MIN_TEMPO_BPM
import app.moviestudio.SEQUENCER_STEPS_PER_MEASURE
import app.moviestudio.SEQUENCER_VISIBLE_OCTAVES
import app.moviestudio.ResolutionOrientation
import app.moviestudio.SUPPORTED_IMAGE_MODELS
import app.moviestudio.SUPPORTED_MUSIC_GENDERS
import app.moviestudio.SUPPORTED_SFX_MODELS
import app.moviestudio.SUPPORTED_VIDEO_SIZES
import app.moviestudio.TTS_DEFAULT_PITCH
import app.moviestudio.TTS_DEFAULT_SPEED
import app.moviestudio.TTS_MAX_PITCH
import app.moviestudio.TTS_MAX_SPEED
import app.moviestudio.TTS_MIN_PITCH
import app.moviestudio.TTS_MIN_SPEED
import app.moviestudio.SequencerNote
import app.moviestudio.UploadedDeviceFile
import app.moviestudio.VOICE_INSTRUCTION_PRESETS
import app.moviestudio.VoiceClone
import app.moviestudio.VoiceDesign
import app.moviestudio.VoiceOptions
import app.moviestudio.aspectRatioLabelFor
import app.moviestudio.cancelMicRecording
import app.moviestudio.closestSizeForAspect
import app.moviestudio.imageModelById
import app.moviestudio.isPitchInScale
import app.moviestudio.isResolutionValidForModel
import app.moviestudio.parseResolution
import app.moviestudio.presetsFor
import app.moviestudio.resolutionOrientation
import app.moviestudio.validateResolutionForModel
import app.moviestudio.playSequencerTone
import app.moviestudio.sequencerRowFrequency
import app.moviestudio.startMicRecording
import app.moviestudio.voicesUsedInMovie
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

private val setupJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * The "generate video / image" dialog (simple yet powerful): a prompt plus optional inputs — a
 * start image (with an optional end image for I2V), reference images, saved characters and
 * scenes. The WAN 2.7 model is selected predictably from what the user attaches (T2V / I2V / R2V)
 * and shown live. The full setup is stored on the generated asset so it can be retried or tweaked
 * later.
 */
@Composable
fun GenerateMediaDialog(
    viewModel: AppViewModel,
    initialAsset: Asset?,
    // When true the dialog edits [initialAsset] in place (the previous media is pushed onto the
    // asset's history). When false it generates: a placeholder asset (no media yet) is still filled
    // in place, while regenerating real media saves the result as a brand-new asset.
    tweak: Boolean = false,
    onDismiss: () -> Unit
) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        } ?: GenerationSetup(kind = "video", prompt = initialAsset?.description ?: "")
    }

    // For an existing media asset (whether we tweak it in place or regenerate into a new asset),
    // start in the mode matching its type (image on "Image", video on "Video") and pre-select that
    // very asset as the base media — so an edit repaints the thing the user opened and a regenerate
    // still starts from that image/video as the start/base image.
    val baseImage = initialAsset?.type == AssetType.IMAGE && initialAsset.ossUrl.isNotBlank()
    val baseVideo = initialAsset?.type == AssetType.VIDEO && initialAsset.ossUrl.isNotBlank()

    var kind by remember {
        mutableStateOf(
            when {
                baseImage -> "image"
                baseVideo -> "video"
                initialSetup.kind == "image" -> "image"
                else -> "video"
            }
        )
    }
    var prompt by remember { mutableStateOf(initialSetup.prompt) }
    var negativePrompt by remember { mutableStateOf(initialSetup.negativePrompt) }
    var imageUrl by remember {
        mutableStateOf(if (baseImage) initialAsset!!.ossUrl else initialSetup.imageUrl)
    }
    // Optional end image (I2V last frame): the clip interpolates from the start image to it.
    var endImageUrl by remember { mutableStateOf(initialSetup.endImageUrl) }
    // Optional videos used as the I2V start/end frames instead of a still image: the start frame is
    // the source video's LAST frame and the end frame is the source video's FIRST frame (the server
    // extracts the still frame before generating). Mutually exclusive with the still start/end
    // image above — picking one clears the other for that slot.
    var startFrameVideoUrl by remember { mutableStateOf(initialSetup.startFrameVideoUrl) }
    var endFrameVideoUrl by remember { mutableStateOf(initialSetup.endFrameVideoUrl) }
    var videoUrl by remember {
        mutableStateOf(if (baseVideo) initialAsset.ossUrl else initialSetup.videoUrl)
    }
    var characterIds by remember { mutableStateOf(initialSetup.characterIds) }
    var sceneIds by remember { mutableStateOf(initialSetup.sceneIds) }
    var referenceImages by remember { mutableStateOf(initialSetup.referenceImages) }
    var duration by remember {
        // Placeholders carry no stored generation setup, so pre-fill the duration slider from the
        // asset's own length (it fills in place); real regenerations use their stored setup.
        val initial = if (initialAsset != null && initialAsset.isDescriptionOnly) {
            initialAsset.durationSeconds
        } else {
            initialSetup.durationSeconds
        }
        mutableStateOf(initial.coerceIn(2.0, 15.0))
    }
    // The selected image-generation model (multi-model support). Video generation always uses the
    // WAN 2.7 family, so this only drives image generation; a regenerate keeps the stored model,
    // while a fresh generation picks up the movie's last-used image model, if any.
    var imageModel by remember {
        mutableStateOf(
            if (initialAsset != null && initialSetup.model.isNotBlank()) {
                imageModelById(initialSetup.model)
            } else {
                imageModelById(viewModel.currentMovie?.lastImageModel ?: initialSetup.model)
            }
        )
    }
    // Whether the combined model & resolution dialog (image generation only) is open.
    var showModelResolution by remember { mutableStateOf(false) }
    // Whether the video resolution dialog (video generation only) is open.
    var showVideoResolution by remember { mutableStateOf(false) }
    // New generations default to the movie's last-used resolution for that media kind, falling
    // back to the size whose aspect is closest to the movie's aspect ratio when none was
    // remembered yet; regenerations keep the size they were originally made with. Image sizes come
    // from the chosen model's own presets, video sizes from the shared WAN tier list.
    val movieAspect = viewModel.currentMovie?.aspectRatio ?: "16:9"
    fun defaultResolutionFor(kind: String, model: ImageModel): String = if (kind == "image") {
        viewModel.currentMovie?.lastImageResolution?.takeIf { isResolutionValidForModel(it, model) }
            ?: closestSizeForAspect(model.presetResolutions, movieAspect)
    } else {
        viewModel.currentMovie?.lastVideoResolution?.takeIf { it in SUPPORTED_VIDEO_SIZES }
            ?: closestSizeForAspect(SUPPORTED_VIDEO_SIZES, movieAspect)
    }
    var resolution by remember {
        mutableStateOf(
            if (initialAsset != null && initialSetup.resolution.isNotBlank()) {
                initialSetup.resolution
            } else {
                defaultResolutionFor(initialSetup.kind, imageModel)
            }
        )
    }

    var uploadingReference by remember { mutableStateOf(false) }

    // A start frame is present when either a still start image or a start-frame video is chosen;
    // the end frame (still image or video) is only meaningful once a start frame exists (I2V).
    val hasStartFrame = imageUrl != null || startFrameVideoUrl != null
    val setup = GenerationSetup(
        kind = kind,
        prompt = prompt,
        negativePrompt = negativePrompt,
        imageUrl = imageUrl,
        // The end image only applies to image-to-video generation (a start frame is present).
        endImageUrl = if (kind == "video" && hasStartFrame) endImageUrl else null,
        // Video-sourced I2V start/end frames only apply to video generation, and the end frame
        // only once a start frame is present.
        startFrameVideoUrl = if (kind == "video") startFrameVideoUrl else null,
        endFrameVideoUrl = if (kind == "video" && hasStartFrame) endFrameVideoUrl else null,
        // The base video only applies to video generation (switches to the video-edit model).
        videoUrl = if (kind == "video") videoUrl else null,
        referenceImages = referenceImages,
        characterIds = characterIds,
        sceneIds = sceneIds,
        durationSeconds = duration,
        resolution = resolution,
        // Only image generation exposes a model choice; video always uses the WAN 2.7 family.
        model = if (kind == "image") imageModel.id else ""
    )
    val modelKind = setup.resolveVideoModelKind()
    val modelLabel = when {
        kind == "image" && !imageUrl.isNullOrBlank() -> "Image edit (image-to-image)"
        kind == "image" && (referenceImages.isNotEmpty() || characterIds.isNotEmpty() || sceneIds.isNotEmpty()) -> "Qwen R2I (Reference-to-image)"
        kind == "image" -> "Text-to-image"
        modelKind == "videoedit" -> "WAN 2.7 Video edit"
        modelKind == "i2v" -> "WAN 2.7 I2V (image-to-video)"
        modelKind == "r2v" -> "WAN 2.7 R2V (reference-to-video)"
        else -> "WAN 2.7 T2V (text-to-video)"
    }

    // Keep the selected size valid when switching between video and image generation, or when the
    // image model changes. A fresh generation (no initial asset) always picks up the movie's
    // last-used resolution for the newly selected kind; an existing asset only has its resolution
    // recalculated when it becomes invalid for the new model, otherwise it keeps the size it was
    // originally made with. Image sizes are validated against the chosen model's own limits; video
    // sizes against the WAN tiers.
    LaunchedEffect(kind, imageModel) {
        if (initialAsset == null) {
            resolution = defaultResolutionFor(kind, imageModel)
        } else if (kind == "image") {
            if (!isResolutionValidForModel(resolution, imageModel)) {
                resolution = closestSizeForAspect(imageModel.presetResolutions, movieAspect)
            }
        } else if (resolution !in SUPPORTED_VIDEO_SIZES) {
            resolution = closestSizeForAspect(SUPPORTED_VIDEO_SIZES, movieAspect)
        }
    }

    val imageAssets = viewModel.libraryAssets.filter { it.type == AssetType.IMAGE && it.ossUrl.isNotBlank() }
    val videoAssets = viewModel.libraryAssets.filter { it.type == AssetType.VIDEO && it.ossUrl.isNotBlank() }
    // Combined image+video list in library order (not images-first then videos) for mixed pickers.
    val visualAssets = viewModel.libraryAssets.filter {
        (it.type == AssetType.IMAGE || it.type == AssetType.VIDEO) && it.ossUrl.isNotBlank()
    }

    // Toggleable "This movie" filters for the media pickers below (base/start/end image, base
    // video, reference images, and saved characters/scenes), mirroring the library panel's own
    // filter. Each defaults to on so a picker first offers only items created for the open movie;
    // toggling reveals the whole library. Assets, characters and scenes all remember which movie
    // they were created for via their own `movieId` field.
    val currentMovieId = viewModel.currentMovie?.id
    var baseImageThisMovie by remember { mutableStateOf(true) }
    var baseVideoThisMovie by remember { mutableStateOf(true) }
    var startImageThisMovie by remember { mutableStateOf(true) }
    var endImageThisMovie by remember { mutableStateOf(true) }
    var referenceThisMovie by remember { mutableStateOf(true) }
    var charactersScenesThisMovie by remember { mutableStateOf(true) }

    // Video editing repaints the base clip and keeps its length, so whenever a base video is
    // chosen (or one is pre-selected) preset the duration to that base video's own duration.
    LaunchedEffect(videoUrl, kind) {
        if (kind == "video") {
            val base = videoUrl ?: return@LaunchedEffect
            val baseAsset = videoAssets.firstOrNull { it.ossUrl == base }
                ?: initialAsset?.takeIf { it.ossUrl == base }
            baseAsset?.durationSeconds?.takeIf { it > 0 }?.let { duration = it }
        }
    }

    // Placeholder assets (description only, no media yet) always fill in place — generating
    // replaces the placeholder rather than spawning a new asset. Only real media that already has
    // output is "regenerated" into a brand-new asset (leaving the original untouched).
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = when {
            tweak -> "Edit"
            regenerating -> "Regenerate video or image"
            else -> "Generate video or image"
        },
        onDismiss = onDismiss,
        width = 620.dp
    ) {
        // Explain how this differs from tweaking: regenerating produces a brand-new asset from the
        // stored setup, while editing repaints this asset in place and keeps the old version in its
        // history. Placeholders (no media yet) simply fill in place, so no note is needed for them.
        if (tweak || regenerating) {
            Text(
                if (tweak) {
                    "Editing changes this asset in place — the current media is saved to this " +
                        "asset's history so you can restore it later."
                } else {
                    "Regenerating creates a new asset from these settings — this asset is left " +
                        "unchanged."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }

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
                if (imageAssets.isNotEmpty()) {
                    ThisMovieFilterButton(baseImageThisMovie) { baseImageThisMovie = !baseImageThisMovie }
                }
                imageAssets.filter { !baseImageThisMovie || it.movieId == currentMovieId }.take(12).forEach { image ->
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
                if (videoAssets.isNotEmpty()) {
                    ThisMovieFilterButton(baseVideoThisMovie) { baseVideoThisMovie = !baseVideoThisMovie }
                }
                videoAssets.filter { !baseVideoThisMovie || it.movieId == currentMovieId }.take(12).forEach { video ->
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

            SectionLabel("Start image or video (switches to I2V)")
            // Preview of the currently attached start frame: a still image, or the source video
            // whose LAST frame will be used as the first frame.
            imageUrl?.let { url ->
                ImageThumbnail(url, size = 96.dp) { imageUrl = null }
                Spacer(Modifier.height(6.dp))
            }
            startFrameVideoUrl?.let { url ->
                Text(
                    "Using the last frame of this video as the start frame.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                VideoPreview(url, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (imageUrl == null && startFrameVideoUrl == null) {
                    PillButton("None", compact = true) { }
                } else {
                    GhostPillButton("None", compact = true) {
                        imageUrl = null
                        startFrameVideoUrl = null
                    }
                }
                if (visualAssets.isNotEmpty()) {
                    ThisMovieFilterButton(startImageThisMovie) { startImageThisMovie = !startImageThisMovie }
                }
                // Images and videos mixed in library order (not images-first). A video contributes
                // its LAST frame as the start frame (continue from where the clip ended).
                visualAssets.filter { !startImageThisMovie || it.movieId == currentMovieId }.take(12).forEach { asset ->
                    if (asset.type == AssetType.IMAGE) {
                        val selected = imageUrl == asset.ossUrl
                        val label = "🖼 " + (asset.description ?: "image").take(18)
                        if (selected) {
                            PillButton(label, compact = true) { imageUrl = null }
                        } else {
                            GhostPillButton(label, compact = true) {
                                imageUrl = asset.ossUrl
                                // The start frame is either a still image or a video, never both.
                                startFrameVideoUrl = null
                            }
                        }
                    } else {
                        val selected = startFrameVideoUrl == asset.ossUrl
                        val label = "🎬 " + (asset.description ?: "video").take(18)
                        if (selected) {
                            PillButton(label, compact = true) { startFrameVideoUrl = null }
                        } else {
                            GhostPillButton(label, compact = true) {
                                startFrameVideoUrl = asset.ossUrl
                                imageUrl = null
                            }
                        }
                    }
                }
            }

            // End image: an optional last frame for I2V — the clip interpolates from the start
            // frame to it. It can be a still image or a video (whose FIRST frame is used, to lead
            // into where that clip begins). Only offered once a start frame (I2V) is chosen.
            if (hasStartFrame) {
                SectionLabel("End image or video (optional — I2V last frame)")
                // Preview of the currently attached end frame.
                endImageUrl?.let { url ->
                    ImageThumbnail(url, size = 96.dp) { endImageUrl = null }
                    Spacer(Modifier.height(6.dp))
                }
                endFrameVideoUrl?.let { url ->
                    Text(
                        "Using the first frame of this video as the end frame.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    VideoPreview(url, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (endImageUrl == null && endFrameVideoUrl == null) {
                        PillButton("None", compact = true) { }
                    } else {
                        GhostPillButton("None", compact = true) {
                            endImageUrl = null
                            endFrameVideoUrl = null
                        }
                    }
                    if (visualAssets.isNotEmpty()) {
                        ThisMovieFilterButton(endImageThisMovie) { endImageThisMovie = !endImageThisMovie }
                    }
                    // Images and videos mixed in library order (not images-first). A video
                    // contributes its FIRST frame as the end frame (lead into where the clip begins).
                    visualAssets.filter { !endImageThisMovie || it.movieId == currentMovieId }.take(12).forEach { asset ->
                        if (asset.type == AssetType.IMAGE) {
                            val selected = endImageUrl == asset.ossUrl
                            val label = "🖼 " + (asset.description ?: "image").take(18)
                            if (selected) {
                                PillButton(label, compact = true) { endImageUrl = null }
                            } else {
                                GhostPillButton(label, compact = true) {
                                    endImageUrl = asset.ossUrl
                                    // The end frame is either a still image or a video, never both.
                                    endFrameVideoUrl = null
                                }
                            }
                        } else {
                            val selected = endFrameVideoUrl == asset.ossUrl
                            val label = "🎬 " + (asset.description ?: "video").take(18)
                            if (selected) {
                                PillButton(label, compact = true) { endFrameVideoUrl = null }
                            } else {
                                GhostPillButton(label, compact = true) {
                                    endFrameVideoUrl = asset.ossUrl
                                    endImageUrl = null
                                }
                            }
                        }
                    }
                }
            }
        }

        SectionLabel(if (kind == "image") "Characters & scenes (switches to R2I)" else "Characters & scenes (switches to R2V)")
            // Preview of the selected characters and scenes: each shows its reference image (or an
            // emoji placeholder when it has none) with a removable name chip beneath.
            val selectedCharacters = viewModel.characters.filter { it.id in characterIds }
            val selectedScenes = viewModel.scenes.filter { it.id in sceneIds }
            if (selectedCharacters.isNotEmpty() || selectedScenes.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedCharacters.forEach { character ->
                        ReferencePreview(
                            emoji = "👤",
                            name = character.name,
                            imageUrl = character.referenceImages.firstOrNull()
                        ) { characterIds = characterIds - character.id }
                    }
                    selectedScenes.forEach { scene ->
                        ReferencePreview(
                            emoji = "🏞️",
                            name = scene.name,
                            imageUrl = scene.referenceImages.firstOrNull()
                        ) { sceneIds = sceneIds - scene.id }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (viewModel.characters.isNotEmpty() || viewModel.scenes.isNotEmpty()) {
                    ThisMovieFilterButton(charactersScenesThisMovie) {
                        charactersScenesThisMovie = !charactersScenesThisMovie
                    }
                }
                viewModel.characters
                    .filter { !charactersScenesThisMovie || it.movieId == currentMovieId }
                    .forEach { character ->
                        val selected = character.id in characterIds
                        val label = "👤 ${character.name}"
                        if (selected) {
                            PillButton(label, compact = true) { characterIds = characterIds - character.id }
                        } else {
                            GhostPillButton(label, compact = true) { characterIds = characterIds + character.id }
                        }
                    }
                viewModel.scenes
                    .filter { !charactersScenesThisMovie || it.movieId == currentMovieId }
                    .forEach { scene ->
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

            SectionLabel(if (kind == "image") "Extra reference images (R2I)" else "Extra reference images (R2V)")
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
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Custom reference images: upload any picture straight from the device.
                GhostPillButton(
                    if (uploadingReference) "Uploading..." else "📤 Upload",
                    compact = true,
                    enabled = !uploadingReference
                ) {
                    uploadingReference = true
                    viewModel.uploadReferenceImage { url ->
                        uploadingReference = false
                        if (url != null) referenceImages = referenceImages + url
                    }
                }
                if (imageAssets.isNotEmpty()) {
                    ThisMovieFilterButton(referenceThisMovie) { referenceThisMovie = !referenceThisMovie }
                }
                val visibleImages = imageAssets.filter { !referenceThisMovie || it.movieId == currentMovieId }.take(12)
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

            if (kind == "video") {
                Spacer(Modifier.height(6.dp))
                if (modelKind == "videoedit") {
                    // Video editing repaints the base clip frame-for-frame, so the output length is
                    // fixed to the base video's — the duration is shown read-only, not adjustable.
                    Text(
                        "Duration: ${duration.roundToInt()}s — matches the base video",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LabeledSlider(
                        label = "Duration",
                        value = duration.toFloat(),
                        valueRange = 2f..15f,
                        valueText = "${duration.roundToInt()}s",
                        onValueChange = { duration = it.toDouble() }
                    )
                }
            }

        // Model & resolution: image generation folds both into a single button that opens a
        // dedicated dialog (the model picker plus the full resolution UI). Video generation opens
        // its own resolution dialog (same orientation-grouped presets, no model picker); video
        // editing inherits the base video's resolution, so it shows no picker at all.
        if (kind == "image") {
            ModelAndResolutionField(
                model = imageModel,
                resolution = resolution,
                onClick = { showModelResolution = true }
            )
        } else if (modelKind != "videoedit") {
            ResolutionField(
                resolution = resolution,
                onClick = { showVideoResolution = true }
            )
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                if (tweak) "✨ Apply edit" else "✨ Generate",
                // Image generation is blocked until the (possibly custom) size fits the chosen model.
                enabled = prompt.isNotBlank() &&
                    (kind != "image" || isResolutionValidForModel(resolution, imageModel))
            ) {
                // Editing a placeholder (or tweaking real media) fills the existing asset in place;
                // only regenerating real media targets a brand-new asset. Either way the job is
                // tagged with the originating asset so its details dialog can show a spinner.
                viewModel.generateMedia(
                    setup,
                    assetId = if (tweak || !regenerating) initialAsset?.id else null,
                    sourceAssetId = initialAsset?.id
                )
                onDismiss()
            }
        }
    }

    // The combined model & resolution dialog opened from the single field above (image generation
    // only): it holds the model picker and the full resolution UI so the main dialog stays compact.
    if (showModelResolution) {
        ModelAndResolutionDialog(
            model = imageModel,
            onModelChange = { imageModel = it },
            resolution = resolution,
            onResolutionChange = { resolution = it },
            onDismiss = { showModelResolution = false }
        )
    }

    // The video resolution dialog opened from the resolution field above (video generation only):
    // the same orientation-grouped preset UI as the image picker, minus the model section.
    if (showVideoResolution) {
        VideoResolutionDialog(
            resolution = resolution,
            onResolutionChange = { resolution = it },
            onDismiss = { showVideoResolution = false }
        )
    }
}

/**
 * A compact preview of a selected character or scene shown above the "Characters & scenes" picker:
 * its first reference image (or an emoji placeholder when it has none) with a removable name chip
 * beneath. Removing it via the chip deselects the character/scene through [onRemove].
 */
@Composable
private fun ReferencePreview(
    emoji: String,
    name: String,
    imageUrl: String?,
    onRemove: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (imageUrl != null) {
            ImageThumbnail(imageUrl, size = 72.dp)
        } else {
            // No reference image yet — a simple emoji placeholder keeps the preview consistent.
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 28.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        RemovableChip("$emoji ${name.take(14)}") { onRemove() }
    }
}

/**
 * Compact summary field that folds the image model and resolution into a single tappable row —
 * showing the chosen [model]'s name, the [resolution]'s aspect label and its "W×H" size — and
 * opens the combined [ModelAndResolutionDialog] via [onClick]. Clipped before the clickable per the
 * project's rounded-hover guideline.
 */
@Composable
private fun ModelAndResolutionField(
    model: ImageModel,
    resolution: String,
    onClick: () -> Unit
) {
    Text(
        "Model & resolution",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioFieldShape) // clip BEFORE clickable so hover has rounded corners
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.displayName,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${aspectRatioLabelFor(resolution)} \u00B7 ${resolution.replace('*', '\u00D7')}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text("\u25BE", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Compact summary field that shows a resolution's aspect label and its "W×H" size and opens the
 * [VideoResolutionDialog] via [onClick]. The video counterpart of [ModelAndResolutionField], minus
 * the model (video generation always uses the WAN 2.7 family). Clipped before the clickable per
 * the project's rounded-hover guideline.
 */
@Composable
private fun ResolutionField(
    resolution: String,
    onClick: () -> Unit
) {
    Text(
        "Resolution",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(StudioFieldShape) // clip BEFORE clickable so hover has rounded corners
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${aspectRatioLabelFor(resolution)} \u00B7 ${resolution.replace('*', '\u00D7')}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text("\u25BE", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The full-dialog resolution picker for video generation, opened from [ResolutionField]. Mirrors
 * [ImageResolutionPicker]'s orientation-grouped sections (Landscape / Portrait / Square) over the
 * shared [SUPPORTED_VIDEO_SIZES] tiers, but has no model section and no custom size (video
 * generation always uses the WAN 2.7 tier list, with no per-model resolution limits to validate
 * a custom size against).
 */
@Composable
private fun VideoResolutionDialog(
    resolution: String,
    onResolutionChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    StudioDialog(title = "Resolution", onDismiss = onDismiss, width = 560.dp) {
        ResolutionPresetSections(
            presetsFor = { orientation -> SUPPORTED_VIDEO_SIZES.filter { resolutionOrientation(it) == orientation } },
            resolution = resolution,
            onResolutionChange = onResolutionChange
        )

        DialogActions {
            PillButton("Done") { onDismiss() }
        }
    }
}

/**
 * The combined model & resolution dialog for image generation, opened from
 * [ModelAndResolutionField]. Holds the model picker (with its capability blurb) and the full
 * [ImageResolutionPicker], reporting changes back through [onModelChange] / [onResolutionChange].
 */
@Composable
private fun ModelAndResolutionDialog(
    model: ImageModel,
    onModelChange: (ImageModel) -> Unit,
    resolution: String,
    onResolutionChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    StudioDialog(title = "Model & resolution", onDismiss = onDismiss, width = 560.dp) {
        // Multi-model image generation: choose which text-to-image / image-edit model runs. The
        // picked model also drives which resolution presets and limits apply below.
        SectionLabel("Model")
        DropdownSelector(
            label = null,
            options = SUPPORTED_IMAGE_MODELS,
            selected = model,
            display = { it.displayName }
        ) { onModelChange(it) }
        Text(
            model.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ImageResolutionPicker(
            model = model,
            resolution = resolution,
            onResolutionChange = onResolutionChange
        )

        DialogActions {
            PillButton("Done") { onDismiss() }
        }
    }
}

/**
 * Renders the shared "Landscape / Portrait / Square" preset sections used by both
 * [ImageResolutionPicker] and [VideoResolutionDialog]: for each [ResolutionOrientation], the
 * sizes returned by [presetsFor] are shown as a row of pills (each annotated with its aspect
 * ratio); tapping one reports it through [onResolutionChange]. Empty orientations are skipped.
 */
@Composable
private fun ResolutionPresetSections(
    presetsFor: (ResolutionOrientation) -> List<String>,
    resolution: String,
    onResolutionChange: (String) -> Unit
) {
    listOf(
        ResolutionOrientation.LANDSCAPE to "Landscape",
        ResolutionOrientation.PORTRAIT to "Portrait",
        ResolutionOrientation.SQUARE to "Square"
    ).forEach { (orientation, title) ->
        val presets = presetsFor(orientation)
        if (presets.isNotEmpty()) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { preset ->
                    val label = "${aspectRatioLabelFor(preset)} · ${preset.replace('*', '\u00D7')}"
                    if (preset == resolution) {
                        PillButton(label, compact = true) { }
                    } else {
                        GhostPillButton(label, compact = true) { onResolutionChange(preset) }
                    }
                }
            }
        }
    }
}

/**
 * Resolution picker for image generation: the chosen [model]'s own preset sizes grouped by
 * orientation (each pill annotated with its aspect ratio) plus a custom "WIDTH×HEIGHT" entry that
 * is validated live against the model's per-side and total-pixel limits. Selecting a preset or a
 * valid custom size reports it through [onResolutionChange]; an invalid custom size shows why and
 * leaves the current selection untouched.
 */
@Composable
private fun ImageResolutionPicker(
    model: ImageModel,
    resolution: String,
    onResolutionChange: (String) -> Unit
) {
    SectionLabel("Resolution")

    // Preset sizes for this model, grouped so landscape / portrait / square are easy to scan.
    ResolutionPresetSections(
        presetsFor = { orientation -> model.presetsFor(orientation) },
        resolution = resolution,
        onResolutionChange = onResolutionChange
    )

    // Custom size: two number fields kept in sync with the current selection. A valid pair is
    // applied immediately; an invalid one surfaces the model's own rejection reason.
    SectionLabel("Custom size")
    var width by remember(model, resolution) {
        mutableStateOf(parseResolution(resolution)?.first?.toString() ?: "")
    }
    var height by remember(model, resolution) {
        mutableStateOf(parseResolution(resolution)?.second?.toString() ?: "")
    }
    val candidate = "${width.trim()}*${height.trim()}"
    val error = if (width.isBlank() || height.isBlank()) null else validateResolutionForModel(candidate, model)

    fun apply(newWidth: String, newHeight: String) {
        width = newWidth.filter { it.isDigit() }
        height = newHeight.filter { it.isDigit() }
        val next = "${width.trim()}*${height.trim()}"
        if (width.isNotBlank() && height.isNotBlank() && isResolutionValidForModel(next, model)) {
            onResolutionChange(next)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudioTextField(
            value = width,
            onValueChange = { apply(it, height) },
            modifier = Modifier.width(120.dp),
            label = "Width",
            placeholder = "1024",
            singleLine = true,
            aiGenerate = null
        )
        Text(
            "\u00D7",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        StudioTextField(
            value = height,
            onValueChange = { apply(width, it) },
            modifier = Modifier.width(120.dp),
            label = "Height",
            placeholder = "1024",
            singleLine = true,
            aiGenerate = null
        )
    }
    Text(
        error ?: "Each side ${model.minDimension}\u2013${model.maxDimension}px; total up to ${model.maxPixels} pixels.",
        style = MaterialTheme.typography.bodySmall,
        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp)
    )
}

/**
 * AI music generation via Fun-Music: a complete lyric editor (with its own AI-generate button),
 * a theme field (with its own AI-generate button) and an instrumental switch. Both AI-generate
 * buttons open the reusable [AiPromptDialog], where the prompt can be reviewed and edited before
 * it's sent and the result refined with chat-style follow-ups. With an [initialAsset], the
 * dialog opens pre-filled from that asset's stored generation setup; regenerating real media
 * produces a brand-new asset (the original is left unchanged), while a placeholder asset (no media
 * yet) is filled in place.
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
                    // Regenerating real media saves the result as a new asset; filling a placeholder
                    // (no media yet) edits that placeholder in place. The job is tagged with the
                    // originating asset either way so its details dialog can show a spinner.
                    assetId = if (regenerating) null else initialAsset?.id,
                    sourceAssetId = initialAsset?.id
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

    // Leaving the music editor (Cancel, ✕, Esc, click-outside or after saving) must stop every
    // track that could still be sounding: this composable's own sequencer preview loop is torn
    // down with it on disposal, and the movie's timeline playback (video, music, voice and other
    // audio) is paused here so nothing keeps playing behind the closed editor.
    DisposableEffect(Unit) {
        onDispose {
            playing = false
            viewModel.pause()
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
 * regenerates from it into a brand-new asset (the original asset is left unchanged).
 */
@Composable
fun SoundEffectDialog(viewModel: AppViewModel, initialAsset: Asset? = null, onDismiss: () -> Unit) {
    val initialSetup = remember(initialAsset) {
        initialAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(GenerationSetup.serializer(), it) }.getOrNull()
        }
    }
    var prompt by remember { mutableStateOf(initialSetup?.prompt ?: initialAsset?.description ?: "") }
    var duration by remember {
        // Placeholders carry no stored generation setup, so pre-fill the duration slider from the
        // asset's own length (it fills in place); real regenerations use their stored setup.
        val initial = if (initialAsset != null && initialAsset.isDescriptionOnly) {
            initialAsset.durationSeconds
        } else {
            initialSetup?.durationSeconds ?: 5.0
        }
        mutableStateOf(initial.coerceIn(2.0, 12.0))
    }
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
                    // Regenerating real media saves the result as a new asset; filling a placeholder
                    // (no media yet) edits that placeholder in place. The job is tagged with the
                    // originating asset either way so its details dialog can show a spinner.
                    assetId = if (regenerating) null else initialAsset?.id,
                    sourceAssetId = initialAsset?.id
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
 * dialog opens pre-filled from that asset's stored generation setup; regenerating real media
 * produces a brand-new asset (the original is left unchanged), while a placeholder asset (no media
 * yet) is filled in place.
 */
@Composable
fun TtsDialog(
    viewModel: AppViewModel,
    initialAsset: Asset? = null,
    // When true the dialog edits [initialAsset] in place (the previous media is pushed onto the
    // asset's history). When false it generates: a placeholder asset (no media yet) is still filled
    // in place, while regenerating real media saves the result as a brand-new asset.
    tweak: Boolean = false,
    // Pre-fills the narration text when opening the dialog without an [initialAsset] (e.g.
    // "Generate voice" from a text element). The generated voiceover is saved as a new asset.
    initialText: String = "",
    // The library asset this dialog was launched from when there is no [initialAsset] (e.g. the
    // text asset a "Generate voice" was spun off from). Recorded on the job so that asset's
    // details dialog can show a spinner while the voiceover is being generated.
    sourceAssetId: String? = null,
    onDismiss: () -> Unit
) {
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
                ?: initialText.ifBlank { null }
                ?: ""
        )
    }
    var voice by remember {
        mutableStateOf(
            initialSetup?.voice?.ifBlank { null }
                ?: initialAsset?.voice?.ifBlank { null }
                // Per-movie last-used voice (persisted on the backend); new movies fall back to Cherry.
                ?: viewModel.currentMovie?.lastVoice?.ifBlank { null }
                ?: viewModel.voiceOptions.presets.firstOrNull()?.id
                ?: DEFAULT_VOICE_ID
        )
    }
    var instructions by remember { mutableStateOf(initialSetup?.instructions ?: "") }
    // Speech-rate (speed) and pitch multipliers applied to every voice kind before generating.
    var speed by remember { mutableStateOf(initialSetup?.speed ?: TTS_DEFAULT_SPEED) }
    var pitch by remember { mutableStateOf(initialSetup?.pitch ?: TTS_DEFAULT_PITCH) }
    var showVoiceLibrary by remember { mutableStateOf(false) }

    // Placeholder assets (description only, no media yet) generate rather than regenerate.
    val regenerating = initialAsset != null && !initialAsset.isDescriptionOnly

    StudioDialog(
        title = when {
            tweak -> "Edit speech"
            regenerating -> "Regenerate speech"
            else -> "Text to speech"
        },
        onDismiss = onDismiss,
        width = 540.dp
    ) {
        // Explain how this differs from regenerating: editing repaints this asset in place and
        // keeps the old version in its history, while regenerating produces a brand-new asset from
        // these settings. Placeholders (no media yet) simply fill in place, so no note is needed.
        if (tweak || regenerating) {
            Text(
                if (tweak) {
                    "Editing changes this asset in place — the current media is saved to this " +
                        "asset's history so you can restore it later."
                } else {
                    "Regenerating creates a new asset from these settings — this asset is left " +
                        "unchanged."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
        }
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Narration text",
            placeholder = "In a world where movies make themselves...",
            minLines = 3,
            maxLines = 8,
            showAiButton = true,
            autoFocus = true
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Voice",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    voiceDisplayLabel(viewModel.voiceOptions, voice),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.width(8.dp))
            GhostPillButton("🎙 Voice Library", compact = true) { showVoiceLibrary = true }
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
        Spacer(Modifier.height(10.dp))

        // Speed & pitch (Qwen for Default voices, CosyVoice for Cloned/Voice Design). Both are a
        // 0.5×–2.0× multiplier; 1.0× keeps the voice's natural delivery. Snapped to 0.1× steps.
        Row {
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = "Speed",
                    value = speed.toFloat(),
                    valueRange = TTS_MIN_SPEED.toFloat()..TTS_MAX_SPEED.toFloat(),
                    valueText = "${(speed * 10).roundToInt() / 10.0}×",
                    onValueChange = { speed = (it * 10).roundToInt() / 10.0 }
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = "Pitch",
                    value = pitch.toFloat(),
                    valueRange = TTS_MIN_PITCH.toFloat()..TTS_MAX_PITCH.toFloat(),
                    valueText = "${(pitch * 10).roundToInt() / 10.0}×",
                    onValueChange = { pitch = (it * 10).roundToInt() / 10.0 }
                )
            }
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(
                when {
                    tweak -> "✨ Apply edit"
                    regenerating -> "🔄 Regenerate voiceover"
                    else -> "🗣️ Generate voiceover"
                },
                enabled = text.isNotBlank()
            ) {
                viewModel.generateMedia(
                    GenerationSetup(
                        kind = "tts",
                        prompt = text,
                        voice = voice,
                        instructions = instructions.trim(),
                        speed = speed,
                        pitch = pitch
                    ),
                    // Editing (tweak) or filling a placeholder (no media yet) edits the existing
                    // asset in place; only regenerating real media targets a brand-new asset. The
                    // job is tagged with the originating asset (the edited/regenerated asset, or the
                    // text asset a voiceover was spun off from) so its details dialog can show a
                    // spinner.
                    assetId = if (tweak || !regenerating) initialAsset?.id else null,
                    sourceAssetId = initialAsset?.id ?: sourceAssetId
                )
                onDismiss()
            }
        }
    }

    if (showVoiceLibrary) {
        VoiceLibraryDialog(
            viewModel = viewModel,
            selectedVoice = voice,
            onSelect = { voice = it },
            onDismiss = { showVoiceLibrary = false }
        )
    }
}

private enum class VoiceLibraryTab(val label: String) {
    FROM_MOVIE("🎬 From this movie"),
    DEFAULT("🔊 Default"),
    CLONED("🧬 Cloned"),
    DESIGN("🎨 Voice Design")
}

/** The two Voice Design input modes: a free-form description or CosyVoice's guided dimensions. */
private enum class VoiceDesignMode(val label: String) {
    SIMPLE("Simple"),
    DETAILED("Detailed")
}

// CosyVoice's recommended voice-design dimensions. Combining more of them yields a more precise
// voice; every field is optional. Each list mirrors CosyVoice's example descriptions.
private val VOICE_DESIGN_GENDERS = listOf("Male", "Female", "Neutral")
private val VOICE_DESIGN_AGES =
    listOf("Child (5-12)", "Teenager (13-18)", "Young adult (19-35)", "Middle-aged (36-55)", "Elderly (55+)")
private val VOICE_DESIGN_PITCHES = listOf("High", "Mid", "Low", "Slightly high", "Slightly low")
private val VOICE_DESIGN_SPEEDS = listOf("Fast", "Moderate", "Slow", "Slightly fast", "Slightly slow")
private val VOICE_DESIGN_EMOTIONS =
    listOf("Cheerful", "Calm", "Gentle", "Serious", "Lively", "Composed", "Soothing")
private val VOICE_DESIGN_TIMBRES = listOf("Magnetic", "Crisp", "Husky", "Mellow", "Sweet", "Rich", "Powerful")
private val VOICE_DESIGN_USE_CASES = listOf(
    "News broadcasting", "Advertising", "Audiobook", "Animation character", "Voice assistant", "Documentary narration", "Storytelling"
)

/**
 * Combines the Voice Design inputs the user actually filled out — CosyVoice's guided dimensions
 * plus any free-form [simple] description — into a single description string sent to the API.
 * Every field is optional; blanks are skipped and each dimension keeps enough context
 * (e.g. "High pitch", "for News broadcasting") to stay unambiguous once merged.
 */
private fun buildVoiceDesignDescription(
    simple: String,
    gender: String,
    age: String,
    pitch: String,
    speed: String,
    emotion: String,
    timbre: String,
    useCase: String,
    additionalDetails: String
): String {
    val parts = buildList {
        if (gender.isNotBlank()) add(gender)
        if (age.isNotBlank()) add(age)
        if (pitch.isNotBlank()) add("$pitch pitch")
        if (speed.isNotBlank()) add("$speed speed")
        if (emotion.isNotBlank()) add(emotion)
        if (timbre.isNotBlank()) add("$timbre timbre")
        if (useCase.isNotBlank()) add("for $useCase")
        if (simple.isNotBlank()) add(simple.trim())
        if (additionalDetails.isNotBlank()) add(additionalDetails.trim())
    }
    return parts.joinToString(", ")
}

/**
 * A single optional CosyVoice voice-design dimension: a labelled dropdown whose first entry
 * ("Any") clears the choice. The chosen example description feeds into the combined voice
 * description (see [buildVoiceDesignDescription]).
 */
@Composable
private fun VoiceDesignDimension(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    DropdownSelector(
        label = label,
        options = listOf("") + options,
        selected = selected,
        display = { it.ifBlank { "Any" } },
        modifier = Modifier.fillMaxWidth()
    ) { onSelect(it) }
}

/**
 * Human-readable label for the currently selected voice [voiceId], resolved against the Voice
 * Library ([options]): built-in presets, the user's cloned voices and their designed voices.
 */
private fun voiceDisplayLabel(options: VoiceOptions, voiceId: String): String {
    options.presets.firstOrNull { it.id == voiceId }?.let { return "🔊 ${it.name}" }
    options.clones.firstOrNull { it.qwenVoiceId == voiceId }?.let { return "🧬 ${it.name} (cloned)" }
    options.designs.firstOrNull { it.qwenVoiceId == voiceId }?.let { return "🎨 ${it.name} (designed)" }
    return voiceId
}

/**
 * Resolves a voice id into the emoji / title / subtitle triple used by a [VoiceRow] in the
 * "From this movie" list. Falls back to a plain label when the id is no longer in the library
 * (e.g. a deleted clone still referenced by an older asset).
 */
private fun voiceLibraryEntry(options: VoiceOptions, voiceId: String): Triple<String, String, String> {
    options.presets.firstOrNull { it.id == voiceId }?.let { preset ->
        return Triple(
            "🔊",
            preset.name,
            listOfNotNull(
                preset.description.ifBlank { null },
                preset.languages.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ).joinToString(" • ")
        )
    }
    options.clones.firstOrNull { it.qwenVoiceId == voiceId }?.let { clone ->
        return Triple("🧬", clone.name, "Cloned voice")
    }
    options.designs.firstOrNull { it.qwenVoiceId == voiceId }?.let { design ->
        return Triple("🎨", design.name, design.description.ifBlank { "Designed voice" })
    }
    return Triple("🎙", voiceId, "Used in this movie")
}

/**
 * The Voice Library: pick and preview ("sample") any voice across four tabs — voices already used
 * in the open movie ("From this movie"), the built-in Default Voices (every Qwen3-TTS preset,
 * with its spoken languages), the user's Cloned Voices (Qwen voice cloning) and their Voice Design
 * voices (CosyVoice, designed from a text description). Selecting a voice hands its id back through
 * [onSelect] and closes the dialog.
 */
@Composable
fun VoiceLibraryDialog(
    viewModel: AppViewModel,
    selectedVoice: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Voices already featured on this movie's VOICE assets (most recent first) — the quick-pick list.
    val movieVoiceIds = remember(viewModel.libraryAssets, viewModel.currentMovie?.id) {
        voicesUsedInMovie(viewModel.libraryAssets, viewModel.currentMovie?.id)
    }
    // Open on "From this movie" when the movie already has voices; otherwise the full Default list.
    var tab by remember {
        mutableStateOf(if (movieVoiceIds.isNotEmpty()) VoiceLibraryTab.FROM_MOVIE else VoiceLibraryTab.DEFAULT)
    }
    var showCreateClone by remember { mutableStateOf(false) }
    var deleteClone by remember { mutableStateOf<VoiceClone?>(null) }
    var deleteDesign by remember { mutableStateOf<VoiceDesign?>(null) }

    // Voice Design create form.
    var designName by remember { mutableStateOf("") }
    // Which description input is shown: a free-form "Simple" text or CosyVoice's "Detailed" dimensions.
    var designMode by remember { mutableStateOf(VoiceDesignMode.SIMPLE) }
    // Simple mode: a single free-form description.
    var designPrompt by remember { mutableStateOf("") }
    // Detailed mode: CosyVoice's guided dimensions — all optional (blank = unspecified).
    var designGender by remember { mutableStateOf("") }
    var designAge by remember { mutableStateOf("") }
    var designPitch by remember { mutableStateOf("") }
    var designSpeed by remember { mutableStateOf("") }
    var designEmotion by remember { mutableStateOf("") }
    var designTimbre by remember { mutableStateOf("") }
    var designUseCase by remember { mutableStateOf("") }
    // Detailed mode: free-form text for anything not covered by the dimensions above.
    var designAdditionalDetails by remember { mutableStateOf("") }
    var designing by remember { mutableStateOf(false) }

    // Any in-progress preview stops when the library closes.
    DisposableEffect(Unit) { onDispose { viewModel.stopVoiceSample() } }

    fun choose(voiceId: String) {
        viewModel.stopVoiceSample()
        onSelect(voiceId)
        onDismiss()
    }

    StudioDialog(title = "Voice Library", onDismiss = onDismiss, width = 560.dp) {
        Text(
            "Pick a voice for narration and preview any of them. Say \"Movie\" — never \"Film\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        // Four tabs can overflow the dialog width (especially "From this movie"); allow a gentle
        // horizontal scroll so every tab stays reachable without wrapping awkwardly.
        val tabScroll = rememberScrollState()
        Row(
            modifier = Modifier.horizontalScroll(tabScroll),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            VoiceLibraryTab.entries.forEach { entry ->
                if (entry == tab) {
                    PillButton(entry.label, compact = true) { tab = entry }
                } else {
                    GhostPillButton(entry.label, compact = true) { tab = entry }
                }
            }
        }
        Spacer(Modifier.height(6.dp))

        when (tab) {
            VoiceLibraryTab.FROM_MOVIE -> {
                if (movieVoiceIds.isEmpty()) {
                    EmptyVoiceHint(
                        "No voices used in this movie yet. Generate a voiceover and it will show up here."
                    )
                } else {
                    movieVoiceIds.forEach { voiceId ->
                        val (emoji, title, subtitle) = voiceLibraryEntry(viewModel.voiceOptions, voiceId)
                        VoiceRow(
                            emoji = emoji,
                            title = title,
                            subtitle = subtitle,
                            selected = voiceId == selectedVoice,
                            sampling = viewModel.samplingVoiceId == voiceId,
                            onSample = { toggleSample(viewModel, voiceId) },
                            onSelect = { choose(voiceId) }
                        )
                    }
                }
            }

            VoiceLibraryTab.DEFAULT -> {
                viewModel.voiceOptions.presets.forEach { preset ->
                    VoiceRow(
                        emoji = "🔊",
                        title = preset.name,
                        subtitle = listOfNotNull(
                            preset.description.ifBlank { null },
                            preset.languages.takeIf { it.isNotEmpty() }?.joinToString(", ")
                        ).joinToString(" • "),
                        selected = preset.id == selectedVoice,
                        sampling = viewModel.samplingVoiceId == preset.id,
                        onSample = { toggleSample(viewModel, preset.id) },
                        onSelect = { choose(preset.id) }
                    )
                }
            }

            VoiceLibraryTab.CLONED -> {
                if (viewModel.voiceOptions.clones.isEmpty()) {
                    EmptyVoiceHint("No cloned voices yet. Clone your own voice from a recording or an audio sample.")
                }
                viewModel.voiceOptions.clones.forEach { clone ->
                    VoiceRow(
                        emoji = "🧬",
                        title = clone.name,
                        subtitle = "Cloned voice",
                        selected = clone.qwenVoiceId == selectedVoice,
                        sampling = viewModel.samplingVoiceId == clone.qwenVoiceId,
                        onSample = { toggleSample(viewModel, clone.qwenVoiceId) },
                        onSelect = { choose(clone.qwenVoiceId) },
                        onDelete = { deleteClone = clone }
                    )
                }
                Spacer(Modifier.height(8.dp))
                GhostPillButton("➕ Clone a voice", compact = true) { showCreateClone = true }
            }

            VoiceLibraryTab.DESIGN -> {
                Text(
                    "Describe the voice you want and CosyVoice designs it — no recording needed. " +
                        "Combine more dimensions for a more precise voice; every field is optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                StudioTextField(
                    value = designName,
                    onValueChange = { designName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "Voice name",
                    placeholder = "Old sea captain",
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))

                // Simple (free-form) vs Detailed (CosyVoice's guided dimensions) description input.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    VoiceDesignMode.entries.forEach { mode ->
                        if (mode == designMode) {
                            PillButton(mode.label, compact = true) { designMode = mode }
                        } else {
                            GhostPillButton(mode.label, compact = true) { designMode = mode }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                when (designMode) {
                    VoiceDesignMode.SIMPLE -> {
                        StudioTextField(
                            value = designPrompt,
                            onValueChange = { designPrompt = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "Voice description",
                            placeholder = "A warm, gravelly older man with a slow, weathered storyteller's cadence.",
                            minLines = 2,
                            maxLines = 4
                        )
                    }

                    VoiceDesignMode.DETAILED -> {
                        VoiceDesignDimension("Gender", VOICE_DESIGN_GENDERS, designGender) { designGender = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Age", VOICE_DESIGN_AGES, designAge) { designAge = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Pitch", VOICE_DESIGN_PITCHES, designPitch) { designPitch = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Speed", VOICE_DESIGN_SPEEDS, designSpeed) { designSpeed = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Emotion", VOICE_DESIGN_EMOTIONS, designEmotion) { designEmotion = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Timbre", VOICE_DESIGN_TIMBRES, designTimbre) { designTimbre = it }
                        Spacer(Modifier.height(8.dp))
                        VoiceDesignDimension("Use case", VOICE_DESIGN_USE_CASES, designUseCase) { designUseCase = it }
                        Spacer(Modifier.height(8.dp))
                        StudioTextField(
                            value = designAdditionalDetails,
                            onValueChange = { designAdditionalDetails = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = "Additional details (optional)",
                            placeholder = "Anything else you'd like to add about the voice.",
                            minLines = 2,
                            maxLines = 4
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // Everything the user filled in — across both modes — is merged into one description.
                val designDescription = buildVoiceDesignDescription(
                    simple = designPrompt,
                    gender = designGender,
                    age = designAge,
                    pitch = designPitch,
                    speed = designSpeed,
                    emotion = designEmotion,
                    timbre = designTimbre,
                    useCase = designUseCase,
                    additionalDetails = designAdditionalDetails
                )
                PillButton(
                    if (designing) "Designing..." else "🎨 Design voice",
                    compact = true,
                    enabled = designName.isNotBlank() && designDescription.isNotBlank() && !designing
                ) {
                    designing = true
                    viewModel.createVoiceDesign(designName.trim(), designDescription) { design ->
                        designing = false
                        if (design != null) {
                            designName = ""
                            designPrompt = ""
                            designGender = ""
                            designAge = ""
                            designPitch = ""
                            designSpeed = ""
                            designEmotion = ""
                            designTimbre = ""
                            designUseCase = ""
                            designAdditionalDetails = ""
                            choose(design.qwenVoiceId)
                        }
                    }
                }

                if (viewModel.voiceOptions.designs.isNotEmpty()) {
                    SectionLabel("Your designed voices")
                    viewModel.voiceOptions.designs.forEach { design ->
                        VoiceRow(
                            emoji = "🎨",
                            title = design.name,
                            subtitle = design.description,
                            selected = design.qwenVoiceId == selectedVoice,
                            sampling = viewModel.samplingVoiceId == design.qwenVoiceId,
                            onSample = { toggleSample(viewModel, design.qwenVoiceId) },
                            onSelect = { choose(design.qwenVoiceId) },
                            onDelete = { deleteDesign = design }
                        )
                    }
                }
            }
        }

        DialogActions {
            GhostPillButton("Close") { onDismiss() }
        }
    }

    if (showCreateClone) {
        CreateVoiceCloneDialog(viewModel) { created ->
            showCreateClone = false
            if (created != null) choose(created.qwenVoiceId)
        }
    }
    deleteClone?.let { clone ->
        ConfirmDialog(
            title = "Delete cloned voice?",
            message = "\"${clone.name}\" will no longer be available for narration.",
            confirmLabel = "Delete voice",
            onConfirm = { viewModel.deleteVoiceClone(clone.id) },
            onDismiss = { deleteClone = null }
        )
    }
    deleteDesign?.let { design ->
        ConfirmDialog(
            title = "Delete designed voice?",
            message = "\"${design.name}\" will no longer be available for narration.",
            confirmLabel = "Delete voice",
            onConfirm = { viewModel.deleteVoiceDesign(design.id) },
            onDismiss = { deleteDesign = null }
        )
    }
}

/** Toggles a voice preview: stops it if this voice is already playing, otherwise starts it. */
private fun toggleSample(viewModel: AppViewModel, voiceId: String) {
    if (viewModel.samplingVoiceId == voiceId) viewModel.stopVoiceSample() else viewModel.sampleVoice(voiceId)
}

/** Muted hint shown when a Voice Library tab has no user voices yet. */
@Composable
private fun EmptyVoiceHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

/**
 * A single selectable voice in the [VoiceLibraryDialog]: an emoji, its name + [subtitle]
 * (description / languages), a ▶/⏸ preview toggle and an optional delete affordance. The whole
 * row is clickable to select the voice; per the project guidelines it is clipped to its rounded
 * shape BEFORE the clickable so the hover/press highlight keeps rounded corners.
 */
@Composable
private fun VoiceRow(
    emoji: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    sampling: Boolean,
    onSample: () -> Unit,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape) // clip BEFORE clickable so the hover/press highlight has rounded corners
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 18.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (selected) {
            Text("✓", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(6.dp))
        }
        RoundIconButton(
            glyph = if (sampling) "⏸" else "▶",
            contentDescription = if (sampling) "Stop preview" else "Play preview",
            size = 28.dp,
            onClick = onSample
        )
        if (onDelete != null) {
            Spacer(Modifier.width(2.dp))
            RoundIconButton("🗑", size = 24.dp, onClick = onDelete)
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
    var uploading by remember { mutableStateOf(false) }
    // The finished microphone capture or picked device file, kept until the user names the voice
    // and clones it. Both the "Record your voice" and "Upload audio" options fill this same slot.
    var voiceSample by remember { mutableStateOf<UploadedDeviceFile?>(null) }
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
                PillButton("🔴 Record", compact = true, enabled = !working && !uploading) {
                    scope.launch {
                        voiceSample = null
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
                            voiceSample = uploaded
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

        SectionLabel("Upload audio")
        Text(
            "Or pick an existing recording from your device — a clean 10–60 second clip works best.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        GhostPillButton(
            if (uploading) "Uploading..." else "📤 Upload audio",
            compact = true,
            enabled = !uploading && !working && !recording
        ) {
            uploading = true
            viewModel.uploadVoiceSample { uploaded ->
                uploading = false
                if (uploaded == null) {
                    viewModel.errorMessage = "Upload failed — nothing was picked"
                } else {
                    voiceSample = uploaded
                }
            }
        }
        // Live progress while a recording or a picked audio sample uploads to storage.
        viewModel.uploadState?.let { upload ->
            Spacer(Modifier.height(8.dp))
            UploadProgressBar(upload)
        }
        voiceSample?.let { sample ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "✅ Sample ready (${formatDuration(sample.durationSeconds)})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                PillButton(
                    if (working) "Cloning..." else "🧬 Clone this sample",
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

/**
 * Creates a first-class TEXT element (rendered as styled text on the timeline, not a placeholder).
 * The text can be styled — color, font, size, background — from the clip inspector once it is
 * placed. A one-click voiceover can also be generated from it in the asset details.
 */
@Composable
fun NewTextAssetDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }

    StudioDialog(title = "New text", onDismiss = onDismiss, width = 500.dp) {
        Text(
            "Creates a text element that renders on the timeline. Style its color, font, size and " +
                "background from the clip inspector, add transitions, or generate a voiceover from it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Text",
            placeholder = "The End",
            minLines = 2,
            maxLines = 6
        )
        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Add text", enabled = true) {
                viewModel.addTextAsset(text.trim())
                onDismiss()
            }
        }
    }
}
