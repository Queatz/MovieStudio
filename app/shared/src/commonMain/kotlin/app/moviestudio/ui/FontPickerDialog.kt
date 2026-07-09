package app.moviestudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.moviestudio.BUILT_IN_FONT_FAMILIES
import app.moviestudio.FontCatalogEntry
import app.moviestudio.FontPrefsResponse
import app.moviestudio.FontSearchResponse
import app.moviestudio.NetworkService
import app.moviestudio.fontVariantItalic
import app.moviestudio.fontVariantName
import app.moviestudio.fontVariantWeight
import app.moviestudio.fontWeightLabel
import app.moviestudio.rememberRuntimeFontFamily
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Weights the family offers for the given [italic] style, from its Google Fonts variant names. */
private fun supportedWeights(variants: List<String>, italic: Boolean): List<Int> =
    variants.filter { fontVariantItalic(it) == italic }.map { fontVariantWeight(it) }.distinct().sorted()

/** The supported weight closest to [preferred] (e.g. keep 700 when switching families). */
private fun nearestWeight(weights: List<Int>, preferred: Int): Int =
    weights.minByOrNull { abs(it - preferred) } ?: 400

/** "latin-ext" → "Latin Ext", "chinese-simplified" → "Chinese Simplified". */
private fun subsetDisplayName(subset: String): String =
    subset.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

/** "sans-serif" → "Sans Serif". */
private fun categoryDisplayName(category: String): String =
    category.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

/** Compact style summary shown under a chosen font's name: "Bold" / "Light Italic" / ... */
fun fontVariantSummary(weight: Int, italic: Boolean): String =
    fontWeightLabel(weight).substringBeforeLast(' ') + if (italic) " Italic" else ""

/**
 * Google-Fonts-style font picker used by the text and caption editors: search the catalog by
 * name, filter by supported language (subset) and style category, pin favorites, revisit recently
 * used families, and preview each family rendered in its own typeface. After picking a family the
 * weight/italic controls only offer the variants the font actually ships. Applying a Google font
 * asks the server for its durable OSS-hosted file (downloaded from Google only the first time it
 * is ever used, then shared across all movies).
 */
@Composable
fun FontPickerDialog(
    initialFamily: String,
    initialWeight: Int,
    initialItalic: Boolean,
    onDismiss: () -> Unit,
    onPick: (family: String, fontUrl: String, weight: Int, italic: Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var subset by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<FontSearchResponse?>(null) }
    var prefs by remember { mutableStateOf<FontPrefsResponse?>(null) }

    var selectedFamily by remember { mutableStateOf(initialFamily) }
    var selectedEntry by remember { mutableStateOf<FontCatalogEntry?>(null) }
    var weight by remember { mutableStateOf(initialWeight) }
    var italic by remember { mutableStateOf(initialItalic) }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Debounced catalog search, re-run whenever the query or a filter changes.
    LaunchedEffect(query, subset, category) {
        delay(250)
        searchResult = try {
            NetworkService.searchFonts(query, subset, category)
        } catch (e: Exception) {
            FontSearchResponse(catalogAvailable = false)
        }
    }

    // Pinned/recent sections, plus the catalog entry of the initially selected Google font (so
    // its weight/italic options are known when the picker opens on an existing selection).
    LaunchedEffect(Unit) {
        prefs = try {
            NetworkService.getFontPrefs()
        } catch (e: Exception) {
            FontPrefsResponse()
        }
        if (initialFamily !in BUILT_IN_FONT_FAMILIES) {
            selectedEntry = try {
                NetworkService.searchFonts(query = initialFamily).fonts
                    .firstOrNull { it.family.equals(initialFamily, ignoreCase = true) }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun selectGoogleFont(entry: FontCatalogEntry) {
        selectedFamily = entry.family
        selectedEntry = entry
        error = null
        // Clamp the current weight/italic to what this family actually ships.
        val hasUpright = entry.variants.any { !fontVariantItalic(it) }
        val hasItalic = entry.variants.any { fontVariantItalic(it) }
        italic = when {
            italic && hasItalic -> true
            !hasUpright -> true // italic-only family
            else -> false
        }
        weight = nearestWeight(supportedWeights(entry.variants, italic), weight)
    }

    fun selectBuiltIn(family: String) {
        selectedFamily = family
        selectedEntry = null
        error = null
    }

    fun togglePin(family: String, pinned: Boolean) {
        scope.launch {
            prefs = try {
                NetworkService.pinFont(family, pinned)
            } catch (e: Exception) {
                prefs
            }
        }
    }

    fun apply() {
        val entry = selectedEntry
        if (entry == null) {
            // Studio fonts ship with the app: no download, legacy bold rendering.
            onPick(selectedFamily, "", 700, false)
            return
        }
        applying = true
        error = null
        scope.launch {
            try {
                val font = NetworkService.ensureFont(entry.family, fontVariantName(weight, italic))
                onPick(font.family, font.url, weight, italic)
            } catch (e: Exception) {
                error = "Could not load the font. Please try again."
                applying = false
            }
        }
    }

    StudioDialog(title = "Choose font", onDismiss = onDismiss, width = 560.dp, scrollable = false) {
        StudioTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Search fonts…",
            singleLine = true,
            autoFocus = true
        )
        Spacer(Modifier.height(8.dp))

        // Language (subset) and category filters, built from the catalog's own option lists.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownSelector(
                label = null,
                options = listOf("") + (searchResult?.subsets ?: emptyList()),
                selected = subset,
                display = { if (it.isBlank()) "All languages" else subsetDisplayName(it) },
                modifier = Modifier.weight(1f)
            ) { subset = it }
            DropdownSelector(
                label = null,
                options = listOf("") + (searchResult?.categories ?: emptyList()),
                selected = category,
                display = { if (it.isBlank()) "All categories" else categoryDisplayName(it) },
                modifier = Modifier.weight(1f)
            ) { category = it }
        }
        Spacer(Modifier.height(8.dp))

        val result = searchResult
        val pinnedFamilies = prefs?.pinned?.map { it.family }?.toSet() ?: emptySet()
        val browsing = query.isBlank() && subset.isBlank() && category.isBlank()

        LazyColumn(Modifier.fillMaxWidth().height(300.dp)) {
            // Studio (app-bundled) fonts, filtered by the search box like everything else.
            val studioFonts = BUILT_IN_FONT_FAMILIES.filter { it.contains(query.trim(), ignoreCase = true) }
            if (studioFonts.isNotEmpty() && subset.isBlank() && category.isBlank()) {
                item { FontSectionHeader("Studio fonts") }
                items(studioFonts) { family ->
                    BuiltInFontRow(
                        family = family,
                        selected = selectedEntry == null && selectedFamily == family,
                        onClick = { selectBuiltIn(family) }
                    )
                }
            }
            // Pinned and recently used sections (shown while browsing, like the Google Fonts UI).
            if (browsing) {
                val pinned = prefs?.pinned ?: emptyList()
                if (pinned.isNotEmpty()) {
                    item { FontSectionHeader("Pinned") }
                    items(pinned, key = { "pinned:${it.family}" }) { entry ->
                        GoogleFontRow(entry, pinned = true, selected = selectedFamily == entry.family,
                            onClick = { selectGoogleFont(entry) }, onPinToggle = { togglePin(entry.family, false) })
                    }
                }
                val recent = (prefs?.recent ?: emptyList()).filter { it.family !in pinnedFamilies }
                if (recent.isNotEmpty()) {
                    item { FontSectionHeader("Recently used") }
                    items(recent, key = { "recent:${it.family}" }) { entry ->
                        GoogleFontRow(entry, pinned = false, selected = selectedFamily == entry.family,
                            onClick = { selectGoogleFont(entry) }, onPinToggle = { togglePin(entry.family, true) })
                    }
                }
            }
            when {
                result == null -> item {
                    Text(
                        "Loading fonts…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                !result.catalogAvailable -> item {
                    Text(
                        "The Google Fonts catalog is unavailable. Set GOOGLE_FONTS_API_KEY on the server to browse it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                else -> {
                    item { FontSectionHeader("All fonts") }
                    items(result.fonts, key = { "all:${it.family}" }) { entry ->
                        GoogleFontRow(
                            entry,
                            pinned = entry.family in pinnedFamilies,
                            selected = selectedFamily == entry.family,
                            onClick = { selectGoogleFont(entry) },
                            onPinToggle = { togglePin(entry.family, entry.family !in pinnedFamilies) }
                        )
                    }
                    if (result.totalMatches > result.fonts.size) {
                        item {
                            Text(
                                "Showing ${result.fonts.size} of ${result.totalMatches} fonts — refine your search to see more.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // Weight & style for the selected Google font — only the variants it actually ships.
        selectedEntry?.let { entry ->
            val hasItalic = entry.variants.any { fontVariantItalic(it) }
            val hasUpright = entry.variants.any { !fontVariantItalic(it) }
            val weights = supportedWeights(entry.variants, italic)
            SectionLabel("Weight & style")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DropdownSelector(
                    label = null,
                    options = weights,
                    selected = if (weight in weights) weight else nearestWeight(weights, weight),
                    display = { fontWeightLabel(it) },
                    modifier = Modifier.width(190.dp)
                ) { weight = it }
                Switch(
                    checked = italic,
                    onCheckedChange = { checked ->
                        // Italic-only families stay italic; upright-only families stay upright.
                        val next = if (checked) hasItalic else !hasUpright
                        italic = next
                        weight = nearestWeight(supportedWeights(entry.variants, next), weight)
                    },
                    enabled = hasItalic && hasUpright
                )
                Text(
                    "Italic",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasItalic) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        DialogActions {
            GhostPillButton("Cancel") { onDismiss() }
            ActionSpacer()
            PillButton("Use font", loading = applying) { apply() }
        }
    }
}

@Composable
private fun FontSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 4.dp)
    )
}

/** One catalog family: its name in its own typeface, category + style count, and a pin toggle. */
@Composable
private fun GoogleFontRow(
    entry: FontCatalogEntry,
    pinned: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onPinToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)) // clip BEFORE clickable: rounded hover
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // The family name rendered with the small "menu" subset of the font itself, so the
            // list previews every font the way the Google Fonts site does.
            Text(
                entry.family,
                fontSize = 18.sp,
                fontFamily = rememberRuntimeFontFamily(entry.previewUrl) ?: FontFamily.Default,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${categoryDisplayName(entry.category)} • ${entry.variants.size} style${if (entry.variants.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RoundIconButton(
            if (pinned) "★" else "☆",
            contentDescription = if (pinned) "Unpin font" else "Pin font",
            tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        ) { onPinToggle() }
    }
}

/** One app-bundled ("Studio") font row; these need no download and no variant choices. */
@Composable
private fun BuiltInFontRow(
    family: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp)) // clip BEFORE clickable: rounded hover
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                family,
                fontSize = 18.sp,
                fontFamily = textFontFamily(family),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Studio font",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
