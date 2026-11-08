package com.floattrans.app.core

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * One speech bubble (or caption box) made of several recognised text lines.
 * Translating the bubble as a whole keeps the sentence structure of the comic
 * instead of chopping it into disconnected fragments.
 */
data class TextGroup(
    val text: String,
    val box: RectF,
    val lineCount: Int
)

/**
 * Rebuilds bubbles out of raw OCR lines using only geometry: lines that sit on
 * top of each other, share a column and are close together belong to the same
 * balloon. Works for manga (columns), manhwa (webtoon strips) and manhua alike.
 */
object TextGrouper {

    private const val MAX_LINES = 10
    private const val MAX_CHARS = 480
    private const val MAX_GAP_FACTOR = 0.85f
    private const val MAX_OVERLAP_GAP_FACTOR = 1.4f
    private const val MIN_HORIZONTAL_OVERLAP = 0.35f
    private const val MIN_HEIGHT_RATIO = 0.5f
    private const val MAX_HEIGHT_RATIO = 2.0f

    fun group(lines: List<OcrLine>): List<TextGroup> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val groups = ArrayList<MutableList<OcrLine>>()

        for (line in sorted) {
            val target = groups.firstOrNull { group ->
                group.size < MAX_LINES && belongsTo(group.last(), line)
            }
            if (target != null) {
                target.add(line)
            } else {
                groups.add(mutableListOf(line))
            }
        }

        return groups
            .map { group ->
                TextGroup(
                    text = joinLines(group),
                    box = unionBox(group),
                    lineCount = group.size
                )
            }
            .sortedBy { it.box.top }
    }

    private fun belongsTo(previous: OcrLine, line: OcrLine): Boolean {
        if (line.top < previous.top) return false

        val gap = (line.top - previous.bottom).toFloat()
        val referenceHeight = max(previous.height, line.height).toFloat().coerceAtLeast(1f)
        if (gap > referenceHeight * MAX_GAP_FACTOR) return false
        if (gap < -referenceHeight * MAX_OVERLAP_GAP_FACTOR) return false

        val ratio = previous.height.toFloat() / line.height.coerceAtLeast(1)
        if (ratio < MIN_HEIGHT_RATIO || ratio > MAX_HEIGHT_RATIO) return false

        return horizontalOverlap(previous, line) >= MIN_HORIZONTAL_OVERLAP
    }

    private fun horizontalOverlap(a: OcrLine, b: OcrLine): Float {
        val overlap = (min(a.right, b.right) - max(a.left, b.left)).toFloat()
        if (overlap <= 0f) return 0f
        val reference = min(a.width, b.width).coerceAtLeast(1).toFloat()
        return overlap / reference
    }

    private fun joinLines(group: List<OcrLine>): String = buildString {
        group.forEachIndexed { index, line ->
            if (index > 0) {
                val previous = group[index - 1]
                // Chinese/Japanese/Korean text has no word spaces; adding one
                // between wrapped lines hurts the translator.
                if (!(isCjk(previous.text) && isCjk(line.text))) append(' ')
            }
            append(line.text.trim())
        }
    }.take(MAX_CHARS)

    private fun unionBox(group: List<OcrLine>): RectF {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (line in group) {
            if (line.left < left) left = line.left
            if (line.top < top) top = line.top
            if (line.right > right) right = line.right
            if (line.bottom > bottom) bottom = line.bottom
        }
        if (left == Int.MAX_VALUE) return RectF()
        return RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
    }

    private fun isCjk(text: String): Boolean = text.any { ch ->
        ch in '\u3040'..'\u30FF' || ch in '\u3400'..'\u9FFF' || ch in '\uAC00'..'\uD7A3'
    }
}
