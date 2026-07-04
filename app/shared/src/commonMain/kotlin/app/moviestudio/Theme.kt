package app.moviestudio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontListFontFamily
import androidx.compose.ui.text.font.SystemFontFamily
import app.moviestudio.shared.resources.Res
import app.moviestudio.shared.resources.asap
import app.moviestudio.shared.resources.yuyu
import org.jetbrains.compose.resources.Font

/**
 * Platform-supplied fonts that are appended after "Yuyu" purely to cover code points Yuyu
 * doesn't provide (i.e. emoji).
 *
 * On Android, the OS already ships a native, always-color-correct emoji font that Android's
 * own font-fallback machinery consults automatically for any code point missing from the
 * app's font — so no extra font is needed there. On Desktop (JVM), Compose Multiplatform
 * relies on Skia and there's no guaranteed system color-emoji font available, so the bundled
 * Noto Color Emoji (COLRv1) font is added explicitly. On Web (JS/WasmJs), the default Compose
 * Web text stack handles emoji fallback; see [useBundledAppFonts].
 */
@Composable
internal expect fun emojiFallbackFonts(): List<Font>

/**
 * Whether this target should force text through the bundled app fonts.
 *
 * Web targets intentionally keep the default Compose Web font stack. Forcing every text run
 * through resource fonts prevents the browser/Compose Web fallback path used by other Wasm
 * projects from resolving platform color emoji.
 */
@Composable
internal expect fun useBundledAppFonts(): Boolean

/**
 * The app-wide default [FontFamily].
 *
 * Fonts are listed in fallback order:
 * - "Asap" is the primary text font and supplies all regular text glyphs (Latin, digits,
 *   punctuation and — crucially — the space glyph).
 * - "Yuyu" is kept as a secondary fallback (and for later use) for any code points Asap
 *   doesn't provide but Yuyu does.
 * - Emoji code points, which neither text font provides, are resolved via
 *   [emojiFallbackFonts] — see its documentation for why that differs per platform.
 *
 * Chaining the fonts (rather than using the emoji font alone) is what keeps normal
 * whitespace at its expected width: an emoji font that also carries a space glyph would
 * otherwise render every space at the emoji font's ultra-wide advance.
 */
@Composable
private fun appFontFamily(): FontFamily? = if (useBundledAppFonts()) {
    FontFamily(
        Font(Res.font.asap),
        Font(Res.font.yuyu),
        *emojiFallbackFonts().toTypedArray(),
    )
} else {
    null
}

/**
 * Returns a copy of this [Typography] with [fontFamily] applied to every text style.
 *
 * Most Material3 components (`Button`, `TextButton`, `AlertDialog`'s title/text,
 * `OutlinedTextField`'s label/placeholder, etc.) don't read the ambient [LocalTextStyle]
 * directly for their default look — internally they wrap their content in
 * `ProvideTextStyle(MaterialTheme.typography.someStyle)`, which *replaces* (rather than
 * merges into) the current [LocalTextStyle]. That means installing our font only via
 * [LocalTextStyle] — as the previous implementation did — never reaches that text: those
 * components reset it back to a `Typography` style with `fontFamily = null` first. Baking
 * the font family into every [Typography] style fixes dialogs, buttons and text inputs.
 */
private fun Typography.withFontFamily(fontFamily: FontFamily): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

/**
 * True while the app renders with the dark color scheme. Panels that are *always* dark (the
 * movie preview area and the timeline) don't read this — they keep their fixed dark palette in
 * both themes.
 */
val LocalDarkTheme = compositionLocalOf { false }

/** Studio light palette: airy lavender surfaces with a deep violet primary. */
private val StudioLightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF5A48D0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF17006E),
    secondary = Color(0xFF625B71),
    secondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFB0326E),
    tertiaryContainer = Color(0xFFFFD8E8),
    background = Color(0xFFF4F2FB),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFBF9FF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFE7E0F0),
    onSurfaceVariant = Color(0xFF49454E),
    outline = Color(0xFF7A757F)
)

/** Studio dark palette: charcoal surfaces with a soft periwinkle primary. */
private val StudioDarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFC7BEFF),
    onPrimary = Color(0xFF2A1C82),
    primaryContainer = Color(0xFF4232AF),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFCCC2DB),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = Color(0xFFFFAFD2),
    tertiaryContainer = Color(0xFF8E1156),
    background = Color(0xFF141318),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF1C1B21),
    onSurface = Color(0xFFE6E1E9),
    surfaceVariant = Color(0xFF49454E),
    onSurfaceVariant = Color(0xFFCAC4CF),
    outline = Color(0xFF948F99)
)

/**
 * App theme wrapper that installs the studio color schemes (following the system light/dark
 * preference automatically) and the default app font family app-wide.
 *
 * The font is applied in two places, which together cover every text composable in the
 * app:
 * - Baked into a custom [Typography] passed to [MaterialTheme], so components that derive
 *   their default text style from `MaterialTheme.typography.*` (buttons, dialogs, text
 *   field labels/placeholders, etc.) pick it up.
 * - Merged into [LocalTextStyle], so the many bare `Text(...)` calls that read the ambient
 *   text style directly (and any component content that isn't further overridden) also
 *   pick it up.
 *
 * Regular text is drawn with Asap (with Yuyu as a secondary fallback); emoji code points
 * fall back to the Noto color glyphs.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (darkTheme) StudioDarkColors else StudioLightColors
    val fontFamily = appFontFamily()

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        if (fontFamily == null) {
            MaterialTheme(colorScheme = colorScheme, content = content)
            return@CompositionLocalProvider
        }

        val typography = remember(fontFamily) { Typography().withFontFamily(fontFamily) }
        MaterialTheme(colorScheme = colorScheme, typography = typography) {
            val mergedStyle: TextStyle = LocalTextStyle.current.merge(TextStyle(fontFamily = fontFamily))
            CompositionLocalProvider(
                LocalTextStyle provides mergedStyle,
                content = content
            )
        }
    }
}
