package com.floattrans.app.core

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

data class TranslationResult(
    val lines: List<String>,
    /** Language code actually used, after auto-detection. */
    val sourceLang: String
)

/**
 * Wraps ML Kit's on-device translator. Models are downloaded once per language
 * pair (a few MB, requires internet the first time) and then run fully offline.
 */
class TranslateEngine {

    private val languageId: LanguageIdentifier by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null
    private var translatorTag: String = ""

    suspend fun translateTexts(
        texts: List<String>,
        sourceSetting: String,
        targetSetting: String
    ): TranslationResult {
        if (texts.isEmpty()) return TranslationResult(emptyList(), sourceSetting)

        val target = if (Languages.isSupported(targetSetting)) targetSetting else "id"
        val source = when {
            sourceSetting == Languages.AUTO -> detect(texts)
            Languages.isSupported(sourceSetting) -> sourceSetting
            else -> detect(texts)
        }

        if (source == target) {
            return TranslationResult(texts, source)
        }

        val active = translatorFor(source, target)
        active.downloadModelIfNeeded().await()

        val translated = ArrayList<String>(texts.size)
        for (text in texts) {
            translated.add(runCatching { active.translate(text).await() }.getOrDefault(text))
        }
        return TranslationResult(translated, source)
    }

    private suspend fun detect(texts: List<String>): String {
        val sample = texts.joinToString(" ").take(600)
        if (sample.isBlank()) return "en"
        val candidates = runCatching { languageId.identifyPossibleLanguages(sample).await() }
            .getOrDefault(emptyList())
        for (candidate in candidates) {
            Languages.normalize(candidate.languageTag)?.let { return it }
        }
        return "en"
    }

    private fun translatorFor(source: String, target: String): Translator {
        val tag = "$source->$target"
        translator?.let { if (tag == translatorTag) return it }
        translator?.let { runCatching { it.close() } }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options).also {
            translator = it
            translatorTag = tag
        }
    }

    fun close() {
        runCatching { translator?.close() }
        translator = null
        translatorTag = ""
        runCatching { languageId.close() }
    }
}
