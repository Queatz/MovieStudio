package app.moviestudio.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.moviestudio.AssetType

/**
 * The shared "＋ Add" menu: every way media enters the studio. It is reused by the library (all
 * creation flows) and by the timeline (blank-track click), where it is filtered to the asset types
 * a given track can host.
 *
 * [allowedTypes] restricts which creation flows are offered: `null` shows every flow (the library),
 * otherwise only the flows that produce an asset of one of those types are listed. The Placeholder
 * flow is always offered regardless of the filter, because a placeholder can stand in for any media.
 * [showLibraryItems] toggles the library-only extras (character/scene creation) that make no sense
 * when adding straight onto the timeline.
 *
 * The menu is a bare [DropdownMenu] controlled by [expanded]/[onDismissRequest]; callers supply the
 * anchor (a button in the library, the click position on the timeline) and, optionally, an [offset]
 * so it can pop up at the mouse position.
 */
@Composable
fun AddMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onGenerateMedia: () -> Unit,
    onGenerateMusic: () -> Unit,
    onSequencer: () -> Unit,
    onSoundEffect: () -> Unit,
    onTts: () -> Unit,
    onRecordVoice: () -> Unit,
    onRecordSoundEffect: () -> Unit,
    onRecordMusic: () -> Unit,
    onDescribe: () -> Unit,
    onNewText: () -> Unit,
    onUpload: (AssetType) -> Unit,
    onNewCharacter: () -> Unit = {},
    onNewScene: () -> Unit = {},
    onNewVisualStyle: () -> Unit = {},
    allowedTypes: Set<AssetType>? = null,
    showLibraryItems: Boolean = true,
    offset: DpOffset = DpOffset(0.dp, 0.dp)
) {
    // A creation flow is offered when the filter is absent (library) or when it produces one of the
    // allowed asset types (a given timeline track).
    fun shows(vararg types: AssetType): Boolean =
        allowedTypes == null || types.any { it in allowedTypes }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, offset = offset) {
        @Composable
        fun item(label: String, action: () -> Unit) {
            DropdownMenuItem(text = { Text(label) }, onClick = { onDismissRequest(); action() })
        }
        if (shows(AssetType.VIDEO, AssetType.IMAGE)) item("✨ Generate visual...") { onGenerateMedia() }
        if (shows(AssetType.MUSIC)) item("🎵 Generate music...") { onGenerateMusic() }
        if (shows(AssetType.MUSIC)) item("🎹 Music sequencer...") { onSequencer() }
        if (shows(AssetType.AUDIO)) item("💥 Generate sound effect...") { onSoundEffect() }
        if (shows(AssetType.VOICE)) item("🗣️ Text to speech...") { onTts() }
        if (shows(AssetType.VOICE)) item("🎙️ Record voice...") { onRecordVoice() }
        if (shows(AssetType.AUDIO)) item("💥 Record sound effect...") { onRecordSoundEffect() }
        if (shows(AssetType.MUSIC)) item("🎵 Record music...") { onRecordMusic() }
        if (shows(AssetType.TEXT)) item("📝 New text...") { onNewText() }
        // The Placeholder flow is always offered — a placeholder can stand in for any media type.
        item("📝 Placeholder...") { onDescribe() }
        if (shows(AssetType.VIDEO)) item("📤 Upload video") { onUpload(AssetType.VIDEO) }
        if (shows(AssetType.IMAGE)) item("📤 Upload image") { onUpload(AssetType.IMAGE) }
        if (shows(AssetType.MUSIC)) item("📤 Upload music") { onUpload(AssetType.MUSIC) }
        if (shows(AssetType.AUDIO)) item("📤 Upload sound effect") { onUpload(AssetType.AUDIO) }
        if (shows(AssetType.VOICE)) item("📤 Upload voice recording") { onUpload(AssetType.VOICE) }
        if (showLibraryItems) {
            item("👤 New character") { onNewCharacter() }
            item("🏞️ New scene") { onNewScene() }
            item("🎨 Visual style...") { onNewVisualStyle() }
        }
    }
}

/**
 * The library's "＋ Add" pill: a [PillButton] anchoring the full (unfiltered) [AddMenu] with all
 * creation flows, including the library-only character/scene extras.
 */
@Composable
fun AddMenuButton(
    onGenerateMedia: () -> Unit,
    onGenerateMusic: () -> Unit,
    onSequencer: () -> Unit,
    onSoundEffect: () -> Unit,
    onTts: () -> Unit,
    onRecordVoice: () -> Unit,
    onRecordSoundEffect: () -> Unit,
    onRecordMusic: () -> Unit,
    onDescribe: () -> Unit,
    onNewText: () -> Unit,
    onUpload: (AssetType) -> Unit,
    onNewCharacter: () -> Unit,
    onNewScene: () -> Unit,
    onNewVisualStyle: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        PillButton("＋", compact = true) { expanded = true }
        AddMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            onGenerateMedia = onGenerateMedia,
            onGenerateMusic = onGenerateMusic,
            onSequencer = onSequencer,
            onSoundEffect = onSoundEffect,
            onTts = onTts,
            onRecordVoice = onRecordVoice,
            onRecordSoundEffect = onRecordSoundEffect,
            onRecordMusic = onRecordMusic,
            onDescribe = onDescribe,
            onNewText = onNewText,
            onUpload = onUpload,
            onNewCharacter = onNewCharacter,
            onNewScene = onNewScene,
            onNewVisualStyle = onNewVisualStyle
        )
    }
}
