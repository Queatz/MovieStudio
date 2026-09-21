package app.moviestudio

/**
 * A language the Qwen Model Studio `voice-enrollment` model can clone from (via `language_hints`
 * on CosyVoice v3.5 / Qwen-Audio-TTS). [englishName] is the English label, [nativeName] is the
 * language written in itself, and [flag] is the emoji shown in the speaking-sample picker.
 */
data class VoiceEnrollmentLanguage(
    val code: String,
    val englishName: String,
    val nativeName: String,
    val flag: String,
) {
    /** True when [query] matches the English name or the native name (case-insensitive). */
    fun matchesSearch(query: String): Boolean {
        val needle = query.trim()
        return englishName.contains(needle, ignoreCase = true) ||
            nativeName.contains(needle, ignoreCase = true)
    }

    fun displayLabel(): String = "$flag $englishName"
}

/**
 * Default speaking sample for voice-clone recording. Poetic, roughly twice the original two-sentence
 * example, and long enough to read aloud for about 20 seconds.
 */
const val DEFAULT_VOICE_CLONE_SPEAKING_PROMPT: String =
    "The morning sun rose over the valley, painting the hills in gold. I took a deep breath, " +
        "smiled, and started walking toward bliss, where the light spilled like honey across " +
        "the water and every bird seemed to be singing just for me."

val DEFAULT_VOICE_ENROLLMENT_LANGUAGE: VoiceEnrollmentLanguage = VoiceEnrollmentLanguage(
    code = "en",
    englishName = "English",
    nativeName = "English",
    flag = "🇺🇸",
)

/**
 * Languages accepted by Model Studio `voice-enrollment` (`language_hints` on CosyVoice v3.5-plus
 * and Qwen-Audio-TTS). English is first; the rest are alphabetical by English name.
 */
val VOICE_ENROLLMENT_LANGUAGES: List<VoiceEnrollmentLanguage> = listOf(
    DEFAULT_VOICE_ENROLLMENT_LANGUAGE,
    VoiceEnrollmentLanguage("ar", "Arabic", "العربية", "🇸🇦"),
    VoiceEnrollmentLanguage("zh", "Chinese", "中文", "🇨🇳"),
    VoiceEnrollmentLanguage("fil", "Filipino", "Filipino", "🇵🇭"),
    VoiceEnrollmentLanguage("fr", "French", "Français", "🇫🇷"),
    VoiceEnrollmentLanguage("de", "German", "Deutsch", "🇩🇪"),
    VoiceEnrollmentLanguage("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
    VoiceEnrollmentLanguage("it", "Italian", "Italiano", "🇮🇹"),
    VoiceEnrollmentLanguage("ja", "Japanese", "日本語", "🇯🇵"),
    VoiceEnrollmentLanguage("ko", "Korean", "한국어", "🇰🇷"),
    VoiceEnrollmentLanguage("ms", "Malaysian", "Bahasa Melayu", "🇲🇾"),
    VoiceEnrollmentLanguage("pt", "Portuguese", "Português", "🇵🇹"),
    VoiceEnrollmentLanguage("ru", "Russian", "Русский", "🇷🇺"),
    VoiceEnrollmentLanguage("es", "Spanish", "Español", "🇪🇸"),
    VoiceEnrollmentLanguage("th", "Thai", "ไทย", "🇹🇭"),
    VoiceEnrollmentLanguage("vi", "Vietnamese", "Tiếng Việt", "🇻🇳"),
)

/** Languages whose English or native name contains [query] (blank query returns the full catalog). */
fun filterVoiceEnrollmentLanguages(query: String): List<VoiceEnrollmentLanguage> =
    VOICE_ENROLLMENT_LANGUAGES.filter { it.matchesSearch(query) }

/**
 * User prompt sent to the generic text model to translate a voice-clone speaking sample into
 * [language], keeping the poetic tone and similar length.
 */
fun buildVoiceCloneTranslationPrompt(text: String, language: VoiceEnrollmentLanguage): String =
    "Translate the following voice-cloning speaking sample into ${language.englishName} " +
        "(${language.nativeName}). Keep it poetic and natural to read aloud, at about the same " +
        "length. Return ONLY the translated text, with no quotes, labels, or commentary.\n\n" +
        text

/** Strips wrapping quotes the chat model sometimes adds around a translated speaking sample. */
fun unwrapTranslatedSpeakingPrompt(raw: String): String {
    var text = raw.trim()
    if (text.isEmpty()) return text
    if ((text.startsWith("\"") && text.endsWith("\"")) ||
        (text.startsWith("“") && text.endsWith("”"))
    ) {
        text = text.substring(1, text.length - 1).trim()
    }
    return text
}
