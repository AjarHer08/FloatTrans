package com.floattrans.app.core

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/** Language catalogue derived from the ML Kit translation models actually shipped. */
object Languages {

    const val AUTO = "auto"

    private val labels = mapOf(
        "af" to "Afrikaans",
        "ar" to "Arab",
        "be" to "Belarus",
        "bg" to "Bulgaria",
        "bn" to "Bengali",
        "ca" to "Katalan",
        "cs" to "Ceko",
        "cy" to "Welsh",
        "da" to "Denmark",
        "de" to "Jerman",
        "el" to "Yunani",
        "en" to "Inggris",
        "eo" to "Esperanto",
        "es" to "Spanyol",
        "et" to "Estonia",
        "fa" to "Persia",
        "fi" to "Finlandia",
        "fr" to "Prancis",
        "ga" to "Irlandia",
        "gl" to "Galisia",
        "gu" to "Gujarati",
        "he" to "Ibrani",
        "hi" to "Hindi",
        "hr" to "Kroasia",
        "ht" to "Haiti",
        "hu" to "Hungaria",
        "id" to "Indonesia",
        "is" to "Islandia",
        "it" to "Italia",
        "ja" to "Jepang",
        "ka" to "Georgia",
        "kn" to "Kannada",
        "ko" to "Korea",
        "lt" to "Lituania",
        "lv" to "Latvia",
        "mk" to "Makedonia",
        "mr" to "Marathi",
        "ms" to "Melayu",
        "mt" to "Malta",
        "nl" to "Belanda",
        "no" to "Norwegia",
        "pl" to "Polandia",
        "pt" to "Portugis",
        "ro" to "Rumania",
        "ru" to "Rusia",
        "sk" to "Slovakia",
        "sl" to "Slovenia",
        "sq" to "Albania",
        "sv" to "Swedia",
        "sw" to "Swahili",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tl" to "Filipino",
        "tr" to "Turki",
        "uk" to "Ukraina",
        "ur" to "Urdu",
        "vi" to "Vietnam",
        "zh" to "Tionghoa (Mandarin)",
        "zu" to "Zulu"
    )

    private val supportedCache: List<String> by lazy {
        runCatching { TranslateLanguage.getAllLanguages().toList() }.getOrDefault(emptyList())
            .map { it.lowercase(Locale.ROOT) }
            .distinct()
            .sorted()
    }

    fun supported(): List<String> = supportedCache

    fun isSupported(code: String): Boolean = code in supportedCache

    fun label(code: String): String {
        labels[code]?.let { return it }
        return runCatching {
            val name = Locale.forLanguageTag(code).displayName
            name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }.getOrDefault(code)
    }

    /** Sorted (code, label) pairs for target pickers. */
    fun targetEntries(): List<Pair<String, String>> =
        supported().map { it to label(it) }.sortedBy { it.second }

    /** Source picker: auto detection followed by every supported language. */
    fun sourceEntries(): List<Pair<String, String>> =
        listOf(AUTO to "Deteksi otomatis") + targetEntries()

    /**
     * Turns a BCP-47 tag from ML Kit language identification (e.g. "zh-Hant", "pt-BR")
     * into a code that has a translation model, or null when there is none.
     */
    fun normalize(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val base = tag.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        return if (isSupported(base)) base else null
    }
}
