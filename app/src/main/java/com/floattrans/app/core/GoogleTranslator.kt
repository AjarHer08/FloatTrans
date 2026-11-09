package com.floattrans.app.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Online translator backed by Google's public web translation endpoint.
 *
 * ML Kit's on-device translator is private and offline, but it is a small
 * phrase-based model: short comic fragments such as "검이 가득하게" come out as
 * nonsense ("Saya lembut bahwa saya adalah pedang"). Google's neural translation
 * is far better for Korean/Chinese/Japanese → Indonesian, keeps sentence context
 * and understands honorifics, which is exactly what reading a chapter needs.
 *
 * Everything fails soft: if the request does not work the caller falls back to
 * the offline ML Kit translator.
 */
object GoogleTranslator {

    data class Outcome(
        val texts: List<String>,
        /** Language code Google detected (an [Languages] code when possible). */
        val sourceLang: String
    )

    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val MAX_CHARS = 4500
    private const val CONNECT_TIMEOUT_MS = 7000
    private const val READ_TIMEOUT_MS = 12000

    suspend fun translate(texts: List<String>, source: String, target: String): Outcome? =
        withContext(Dispatchers.IO) {
            val cleaned = texts.map { it.trim() }
            if (cleaned.all { it.isEmpty() }) return@withContext null

            // Send the whole page in one request: the translator then keeps the
            // thread of the conversation across bubbles instead of translating
            // every balloon in isolation.
            batch(cleaned, source, target)?.let { return@withContext it }

            // Some replies reflow or merge lines; fall back to one request each.
            val single = ArrayList<String>(cleaned.size)
            var detected = source
            for (text in cleaned) {
                if (text.isEmpty()) {
                    single.add("")
                    continue
                }
                val one = request(text, source, target) ?: return@withContext null
                single.add(one.first)
                if (one.second != Languages.AUTO) detected = one.second
            }
            if (single.size != cleaned.size) return@withContext null
            Outcome(single, detected)
        }

    private fun batch(texts: List<String>, source: String, target: String): Outcome? {
        val joined = texts.joinToString("\n")
        if (joined.length > MAX_CHARS) return null
        val (translated, detected) = request(joined, source, target) ?: return null
        val parts = translated.split("\n").map { it.trim() }
        if (parts.size != texts.size) return null
        return Outcome(parts, detected)
    }

    private fun request(text: String, source: String, target: String): Pair<String, String>? {
        if (text.isBlank()) return "" to source
        val sl = if (source == Languages.AUTO) "auto" else googleCode(source)
        val query = buildString {
            append(ENDPOINT)
            append("?client=gtx&dt=t&ie=UTF-8&oe=UTF-8")
            append("&sl=").append(encode(sl))
            append("&tl=").append(encode(googleCode(target)))
            append("&q=").append(encode(text))
        }

        val connection = runCatching {
            (URL(query).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FloatTrans")
                setRequestProperty("Accept", "application/json")
            }
        }.getOrNull() ?: return null

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                parse(body)
            }
        } catch (error: Throwable) {
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun parse(body: String): Pair<String, String>? {
        val root = runCatching { JSONArray(body) }.getOrNull() ?: return null
        val segments: JSONArray = runCatching { root.getJSONArray(0) }.getOrNull() ?: return null

        val out = StringBuilder()
        for (index in 0 until segments.length()) {
            val segment = segments.optJSONArray(index) ?: continue
            val piece = segment.optString(0, "")
            if (piece.isNotEmpty()) out.append(piece)
        }
        val translated = out.toString().trim()
        if (translated.isEmpty()) return null

        val rawSource = runCatching { root.optString(2, Languages.AUTO) }
            .getOrDefault(Languages.AUTO)
        return translated to fromGoogleCode(rawSource)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Google speaks "zh-CN"/"zh-TW"; the rest of the app uses ML Kit codes. */
    private fun googleCode(code: String): String = when (code.lowercase(Locale.ROOT)) {
        "zh", "zh-cn", "zh-hans" -> "zh-CN"
        "zh-tw", "zh-hant" -> "zh-TW"
        else -> code
    }

    private fun fromGoogleCode(code: String): String {
        val lower = code.lowercase(Locale.ROOT)
        if (lower == "auto" || lower.isBlank()) return Languages.AUTO
        return when {
            lower.startsWith("zh") -> "zh"
            else -> Languages.normalize(lower) ?: lower.substringBefore('-')
        }
    }
}
