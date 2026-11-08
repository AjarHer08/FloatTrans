package com.floattrans.app.core

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/** One recognised text line plus its position inside the captured bitmap. */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object OcrScript {
    const val AUTO = "auto"
    const val LATIN = "latin"
    const val JAPANESE = "japanese"
    const val KOREAN = "korean"
    const val CHINESE = "chinese"

    val entries: List<Pair<String, String>> = listOf(
        AUTO to "Otomatis (deteksi aksara)",
        LATIN to "Latin (Inggris / Indonesia)",
        JAPANESE to "Jepang (kana + kanji)",
        KOREAN to "Korea (hangul)",
        CHINESE to "Tionghoa (hanzi)"
    )
}

/**
 * Runs ML Kit text recognition on a screenshot.
 *
 * All models are bundled in the APK, so OCR works offline and without Google
 * Play Services. [OcrScript.AUTO] first tries Latin and only pays for the CJK
 * models when the page has little or no Latin text, then picks the script with
 * the best character yield.
 */
class OcrEngine {

    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val japanese: TextRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val korean: TextRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(bitmap: Bitmap, script: String): List<OcrLine> = when (script) {
        OcrScript.LATIN -> read(latin, bitmap)
        OcrScript.JAPANESE -> read(japanese, bitmap)
        OcrScript.KOREAN -> read(korean, bitmap)
        OcrScript.CHINESE -> read(chinese, bitmap)
        else -> autoDetect(bitmap)
    }

    private suspend fun autoDetect(bitmap: Bitmap): List<OcrLine> {
        val latinLines = read(latin, bitmap)
        if (letterCount(latinLines) >= MIN_LATIN_CHARS) return latinLines

        val koreanLines = read(korean, bitmap)
        if (koreanLines.any { containsHangul(it.text) }) return koreanLines

        val japaneseLines = read(japanese, bitmap)
        if (japaneseLines.any { containsKana(it.text) }) return japaneseLines

        val chineseLines = read(chinese, bitmap)
        return when {
            chineseLines.isEmpty() -> japaneseLines
            japaneseLines.isEmpty() -> chineseLines
            letterCount(chineseLines) >= letterCount(japaneseLines) -> chineseLines
            else -> japaneseLines
        }
    }

    private suspend fun read(recognizer: TextRecognizer, bitmap: Bitmap): List<OcrLine> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result: Text = recognizer.process(image).await()
        val lines = ArrayList<OcrLine>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val text = line.text.trim()
                if (text.isEmpty()) continue
                if (box.width() < MIN_BOX_PX || box.height() < MIN_BOX_PX) continue
                lines.add(OcrLine(text, box.left, box.top, box.right, box.bottom))
            }
        }
        return lines
    }

    fun close() {
        runCatching { latin.close() }
        runCatching { japanese.close() }
        runCatching { korean.close() }
        runCatching { chinese.close() }
    }

    private companion object {
        const val MIN_LATIN_CHARS = 6
        const val MIN_BOX_PX = 6

        fun letterCount(lines: List<OcrLine>): Int =
            lines.sumOf { line -> line.text.count { it.isLetterOrDigit() } }

        fun containsHangul(text: String): Boolean = text.any { ch ->
            ch in '\uAC00'..'\uD7A3' || ch in '\u1100'..'\u11FF' || ch in '\u3130'..'\u318F'
        }

        fun containsKana(text: String): Boolean = text.any { ch ->
            ch in '\u3040'..'\u309F' || ch in '\u30A0'..'\u30FF'
        }
    }
}
