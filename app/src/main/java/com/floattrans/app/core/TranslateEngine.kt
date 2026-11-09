package com.floattrans.app.core

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

data class TranslationResult(
    val lines: List<String>,
    /** Language code actually used, after auto-detection. */
    val sourceLang: String,
    /** "online" (Google) or "offline" (ML Kit), for the status line. */
    val engine: String = "offline"
)

/**
 * Translates a page. Preference order is online neural translation (much more
 * accurate for comic dialogue), then ML Kit's on-device models when the network
 * is unavailable so the app still works offline.
 */
class TranslateEngine {

    private val languageId: LanguageIdentifier by lazy { LanguageIdentification.getClient() }
    private var translator: Translator? = null
    private var translatorTag: String = ""

    suspend fun translateTexts(
        texts: List<String>,
        sourceSetting: String,
        targetSetting: String,
        engineSetting: String = Prefs.ENGINE_SMART
    ): TranslationResult {
        if (texts.isEmpty()) return TranslationResult(emptyList(), sourceSetting)

        val target = if (Languages.isSupported(targetSetting)) targetSetting else "id"
        val detected = if (sourceSetting == Languages.AUTO) detectSource(texts, target) else null

        if (engineSetting != Prefs.ENGINE_OFFLINE) {
            // Google misbehaves on "sl=auto" with several bubbles at once (it can
            // leave whole sentences untranslated), so detect locally first and
            // send an explicit source language whenever we know it.
            val onlineSource = when {
                detected != null -> detected
                Languages.isSupported(sourceSetting) -> sourceSetting
                else -> Languages.AUTO
            }
            val online = runCatching {
                GoogleTranslator.translate(texts, onlineSource, target)
            }.getOrNull()
            if (online != null) {
                return TranslationResult(online.texts, online.sourceLang, "online")
            }
        }

        val source = when {
            detected != null -> detected
            Languages.isSupported(sourceSetting) -> sourceSetting
            else -> "en"
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
        return TranslationResult(translated, source, "offline")
    }

    /**
     * Language identification runs locally (no model download needed). A result
     * that matches the target language while the text is clearly in a non-Latin
     * script is ignored, because then the identifier was wrong.
     */
    private suspend fun detectSource(texts: List<String>, target: String): String? {
        val sample = texts.joinToString(" ").take(600)
        if (sample.isBlank()) return null
        val candidates = runCatching { languageId.identifyPossibleLanguages(sample).await() }
            .getOrDefault(emptyList())
        val picked = candidates.firstNotNullOfOrNull { Languages.normalize(it.languageTag) }
            ?: return null
        if (picked == target && hasNonLatinScript(sample)) return null
        return picked
    }

    private fun hasNonLatinScript(text: String): Boolean = text.any { ch ->
        ch in '\uAC00'..'\uD7A3' || ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u9FFF' ||
            ch in '\u0400'..'\u04FF' || ch in '\u0600'..'\u06FF' || ch in '\u0E00'..'\u0E7F'
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
