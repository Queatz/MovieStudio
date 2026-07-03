package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.AppViewModel
import app.moviestudio.Asset
import app.moviestudio.AssetType
import app.moviestudio.GenerationSetup
import app.moviestudio.MusicSequence
import app.moviestudio.NetworkService
import app.moviestudio.SequencerNote
import app.moviestudio.VoiceClone
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

    var kind by remember { mutableStateOf(if (initialSetup.kind == "image") "image" else "video") }
    var prompt by remember { mutableStateOf(initialSetup.prompt) }
    var negativePrompt by remember { mutableStateOf(initialSetup.negativePrompt) }
    var imageUrl by remember { mutableStateOf(initialSetup.imageUrl) }
    var characterIds by remember { mutableStateOf(initialSetup.characterIds) }
    var sceneIds by remember { mutableStateOf(initialSetup.sceneIds) }
    var referenceImages by remember { mutableStateOf(initialSetup.referenceImages) }
    var duration by remember { mutableStateOf(initialSetup.durationSeconds.coerceIn(2.0, 15.0)) }
    var resolution by remember { mutableStateOf(initialSetup.resolution.ifBlank { "1280*720" }) }

    val setup = GenerationSetup(
        kind = kind,
        prompt = prompt,
        negativePrompt = negativePrompt,
        imageUrl = imageUrl,
        referenceImages = referenceImages,
        characterIds = characterIds,
        sceneIds = sceneIds,
        durationSeconds = duration,
        resolution = resolution
    )
    val modelKind = setup.resolveVideoModelKind()
    val modelLabel = when {
        kind == "image" -> "Text-to-image"
        modelKind == "i2v" -> "WAN 2.7 I2V (image-to-video)"
        modelKind == "r2v" -> "WAN 2.7 R2V (reference-to-video)"
        else -> "WAN 2.7 T2V (text-to-video)"
    }

    val imageAssets = viewModel.libraryAssets.filter { it.type == AssetType.IMAGE && it.ossUrl.isNotBlank() }

    StudioDialog(
        title = if (initialAsset != null) "Tweak & regenerate" else "Generate video or image",
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
            placeholder = "A slow cinematic dolly shot through a rain-soaked neon alley...",
            minLines = 2,
            maxLines = 4
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

        if (kind == "video") {
            SectionLabel("Start image (switches to I2V)")
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                imageAssets.take(12).forEach { image ->
                    val selected = image.ossUrl in referenceImages
                    val label = "📎 " + (image.description ?: "image").take(16)
                    if (selected) {
                        PillButton(label, compact = true) { referenceImages = referenceImages - image.ossUrl }
                    } else {
                        GhostPillButton(label, compact = true) { referenceImages = referenceImages + image.ossUrl }
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
            options = if (kind == "image") listOf("1024*1024", "1280*720", "720*1280")
            else listOf("1280*720", "1920*1080", "720*1280", "960*960"),
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
 * a theme field (with its own AI-generate button) and an instrumental switch.
 */
@Composable
fun GenerateMusicDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var theme by remember { mutableStateOf("") }
    var lyric by remember { mutableStateOf("") }
    var instrumental by remember { mutableStateOf(false) }
    var loadingTheme by remember { mutableStateOf(false) }
    var loadingLyrics by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val movieTitle = viewModel.currentMovie?.title ?: ""

    StudioDialog(title = "Generate music", onDismiss = onDismiss, width = 560.dp) {
        StudioTextField(
            value = theme,
            onValueChange = { theme = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Theme",
            placeholder = "Uplifting electro-pop with soaring strings...",
            singleLine = true,
            trailingIcon = {
                if (loadingTheme) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    RoundIconButton("✨", contentDescription = "AI-generate theme", size = 28.dp) {
                        loadingTheme = true
                        scope.launch {
                            try {
                                theme = NetworkService.generateTheme(theme, movieTitle)
                            } catch (e: Exception) {
                                viewModel.errorMessage = "Theme generation failed: ${e.message}"
                            } finally {
                                loadingTheme = false
                            }
                        }
                    }
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
                if (loadingLyrics) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    RoundIconButton("✨", contentDescription = "AI-generate lyrics", size = 28.dp, enabled = !instrumental) {
                        loadingLyrics = true
                        scope.launch {
                            try {
                                lyric = NetworkService.generateLyrics(theme.ifBlank { lyric }, movieTitle)
                            } catch (e: Exception) {
                                viewModel.errorMessage = "Lyric generation failed: ${e.message}"
                            } finally {
                                loadingLyrics = false
                            }
                        }
                    }
                }
            }
        )

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("🎵 Generate music", enabled = theme.isNotBlank() || lyric.isNotBlank()) {
                viewModel.generateMedia(
                    GenerationSetup(kind = "music", theme = theme, lyric = lyric, instrumental = instrumental)
                )
                onDismiss()
            }
        }
    }
}

/**
 * The mini music sequencer: an always-consonant pentatonic grid, synthesized server-side into a
 * WAV music asset. The pattern is stored on the asset so it can be reopened and edited later.
 */
@Composable
fun SequencerDialog(viewModel: AppViewModel, existingAsset: Asset?, onDismiss: () -> Unit) {
    val initial = remember(existingAsset) {
        existingAsset?.generationConfig?.let {
            runCatching { setupJson.decodeFromString(MusicSequence.serializer(), it) }.getOrNull()
        } ?: MusicSequence()
    }
    val rows = 11 // pentatonic rows offered by the synthesizer
    val steps = 16

    var name by remember { mutableStateOf(initial.name) }
    var tempo by remember { mutableStateOf(initial.tempoBpm) }
    var loops by remember { mutableStateOf(initial.loops) }
    var waveform by remember { mutableStateOf(initial.waveform) }
    var notes by remember { mutableStateOf(initial.notes.toSet()) }
    var saving by remember { mutableStateOf(false) }

    StudioDialog(title = "Music sequencer", onDismiss = onDismiss, width = 640.dp) {
        StudioTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Name",
            singleLine = true
        )
        Spacer(Modifier.height(10.dp))

        // The grid: bottom row = lowest note. Rendered top-down.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF17151C))
                .padding(8.dp)
        ) {
            for (row in (rows - 1) downTo 0) {
                Row {
                    for (step in 0 until steps) {
                        val note = SequencerNote(step, row)
                        val active = note in notes
                        val beatShade = if ((step / 4) % 2 == 0) Color(0xFF232030) else Color(0xFF1D1A27)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(20.dp)
                                .padding(1.dp)
                                .clip(RoundedCornerShape(4.dp)) // clip BEFORE clickable
                                .background(if (active) Color(0xFF8F7BFF) else beatShade)
                                .clickable {
                                    notes = if (active) notes - note else notes + note
                                }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row {
            Column(Modifier.weight(1f)) {
                LabeledSlider(
                    label = "Tempo",
                    value = tempo.toFloat(),
                    valueRange = 60f..200f,
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
        DropdownSelector(
            label = "Waveform",
            options = listOf("sine", "square", "saw", "triangle"),
            selected = waveform,
            display = { it.replaceFirstChar { c -> c.uppercase() } }
        ) { waveform = it }

        DialogActions {
            GhostPillButton("Clear", compact = true) { notes = emptySet() }
            Spacer(Modifier.weight(1f))
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton(if (saving) "Rendering..." else "💾 Save as music", enabled = notes.isNotEmpty() && !saving) {
                saving = true
                val sequence = MusicSequence(
                    name = name.ifBlank { "Sequence" },
                    tempoBpm = tempo,
                    steps = steps,
                    loops = loops,
                    waveform = waveform,
                    notes = notes.toList().sortedWith(compareBy({ it.step }, { it.pitch }))
                )
                viewModel.createSequenceAsset(sequence) { onDismiss() }
            }
        }
    }
}

/**
 * Sound-effect generation: WAN 2.7 renders a short video for the prompt and the server extracts
 * its audio track into the sound-effects library.
 */
@Composable
fun SoundEffectDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var prompt by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf(5.0) }

    StudioDialog(title = "Generate sound effect", onDismiss = onDismiss, width = 500.dp) {
        Text(
            "WAN 2.7 generates a short video for your prompt; its audio track is extracted into " +
                "the sound-effects library, ready to clip.",
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
            minLines = 2
        )
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
            PillButton("💥 Generate", enabled = prompt.isNotBlank()) {
                viewModel.generateMedia(GenerationSetup(kind = "sfx", prompt = prompt, durationSeconds = duration))
                onDismiss()
            }
        }
    }
}

/**
 * Text-to-speech: voice selector offering every Qwen preset plus the user's cloned voices, and a
 * "Create voice" button for Qwen voice cloning (China mainland).
 */
@Composable
fun TtsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(viewModel.voiceOptions.presets.firstOrNull() ?: "Cherry") }
    var showCreateVoice by remember { mutableStateOf(false) }

    val voiceEntries: List<Pair<String, String>> =
        viewModel.voiceOptions.presets.map { it to "🔊 $it" } +
            viewModel.voiceOptions.clones.map { it.qwenVoiceId to "🧬 ${it.name} (cloned)" }

    StudioDialog(title = "Text to speech", onDismiss = onDismiss, width = 540.dp) {
        StudioTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            label = "Narration text",
            placeholder = "In a world where movies make themselves...",
            minLines = 3,
            maxLines = 8
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

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("🗣️ Generate voiceover", enabled = text.isNotBlank()) {
                viewModel.generateMedia(GenerationSetup(kind = "tts", prompt = text, voice = voice))
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

/** Voice cloning: pick a sample recording from the device and enroll it as a reusable voice. */
@Composable
fun CreateVoiceCloneDialog(viewModel: AppViewModel, onClose: (VoiceClone?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    StudioDialog(title = "Create voice (cloning)", onDismiss = { onClose(null) }, width = 480.dp) {
        Text(
            "Pick a clean voice sample (10–60s). Qwen voice cloning (China mainland) enrolls it " +
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
                    RoundIconButton("🗑", size = 24.dp) { viewModel.deleteVoiceClone(clone.id) }
                }
            }
        }

        DialogActions {
            GhostPillButton("Cancel") { onClose(null) }
            ActionSpacer()
            PillButton(
                if (working) "Cloning..." else "🎤 Pick sample & clone",
                enabled = name.isNotBlank() && !working
            ) {
                working = true
                viewModel.createVoiceCloneFromDevice(name.trim()) { clone ->
                    working = false
                    if (clone != null) onClose(clone)
                }
            }
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
                "media with one click.",
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
