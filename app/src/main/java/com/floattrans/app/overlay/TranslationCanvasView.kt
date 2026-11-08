package com.floattrans.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import androidx.core.content.res.ResourcesCompat
import com.floattrans.app.R
import com.floattrans.app.core.Prefs
import kotlin.math.min

/**
 * Paints translated text where the original lettering was, styled like comic
 * typesetting rather than like a UI label: a comic lettering face, text centred
 * and fitted inside the bubble, black ink on light bubbles and white ink with a
 * dark contour on dark ones.
 */
class TranslationCanvasView(context: Context) : View(context) {

    data class Item(
        val translated: String,
        val original: String,
        /** Bounding box of the bubble lettering in captured-screenshot coordinates. */
        val box: RectF,
        /** True when the bubble behind the text is light. */
        val lightBackground: Boolean = false,
        /** Average colour of the bubble, used by the solid/outline styles. */
        val background: Int = Color.BLACK
    )

    private val items = mutableListOf<Item>()
    private var captureWidth = 1
    private var captureHeight = 1

    private var textSizeSp = 16f
    private var style = Prefs.STYLE_AUTO
    private var showOriginal = false

    private val density = resources.displayMetrics.density
    private val spScale =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val comicTypeface: Typeface by lazy {
        runCatching { ResourcesCompat.getFont(context, R.font.comicneue_bold) }
            .getOrNull() ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = comicTypeface
        isSubpixelText = true
        color = Color.WHITE
    }

    private val originalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = comicTypeface
        color = 0x99FFFFFF.toInt()
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * density
    }

    fun configure(textSizeSp: Float, style: String, showOriginal: Boolean) {
        this.textSizeSp = textSizeSp
        this.style = style
        this.showOriginal = showOriginal
        invalidate()
    }

    fun setItems(newItems: List<Item>, capWidth: Int, capHeight: Int) {
        items.clear()
        items.addAll(newItems)
        captureWidth = capWidth.coerceAtLeast(1)
        captureHeight = capHeight.coerceAtLeast(1)
        invalidate()
    }

    fun clear() {
        items.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || width <= 0 || height <= 0) return

        val scaleX = width.toFloat() / captureWidth
        val scaleY = height.toFloat() / captureHeight

        for (item in items) {
            val original = RectF(
                item.box.left * scaleX,
                item.box.top * scaleY,
                item.box.right * scaleX,
                item.box.bottom * scaleY
            )
            if (original.width() < 8f || original.height() < 8f) continue

            // OCR boxes hug the glyphs; balloons have breathing room. Growing the
            // box slightly lets the translation fill the balloon like real lettering.
            val padX = min(6f * density + original.width() * 0.06f, 16f * density)
            val padY = min(5f * density + original.height() * 0.08f, 12f * density)
            val box = RectF(
                original.left - padX,
                original.top - padY,
                original.right + padX,
                original.bottom + padY
            )
            box.left = box.left.coerceIn(0f, width.toFloat())
            box.right = box.right.coerceIn(0f, width.toFloat())
            box.top = box.top.coerceIn(0f, height.toFloat())
            box.bottom = box.bottom.coerceIn(0f, height.toFloat())
            if (box.width() < 10f || box.height() < 10f) continue

            drawItem(canvas, item, box)
        }
    }

    private fun drawItem(canvas: Canvas, item: Item, box: RectF) {
        val showSource = showOriginal && item.original.isNotBlank() &&
            !item.original.equals(item.translated, ignoreCase = true)

        val textArea = if (showSource) box.height() * 0.78f else box.height()

        // Start from a scale that fills the balloon, then shrink until it fits.
        val preferred = min(box.height(), box.width() * 0.52f)
            .coerceAtMost(textSizeSp * spScale * 2.4f)
            .coerceAtLeast(9f * density)

        var size = preferred
        var layout = layoutOf(item.translated, size, box.width(), textPaint)
        var guard = 0
        while (guard++ < 20 && layout.height > textArea) {
            size *= 0.85f
            if (size < 7f * density) break
            layout = layoutOf(item.translated, size, box.width(), textPaint)
        }

        val radius = min(box.height() / 2.6f, 12f * density)
        when (style) {
            Prefs.STYLE_SOLID -> {
                backgroundPaint.color = withAlpha(item.background, 0xF0)
                canvas.drawRoundRect(box, radius, radius, backgroundPaint)
            }

            Prefs.STYLE_OUTLINE -> {
                backgroundPaint.color = withAlpha(item.background, 0xF0)
                canvas.drawRoundRect(box, radius, radius, backgroundPaint)
                borderPaint.color =
                    if (item.lightBackground) 0x33000000 else 0x59FFFFFF
                canvas.drawRoundRect(box, radius, radius, borderPaint)
            }

            else -> Unit
        }

        val ink = if (item.lightBackground) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val contour = if (item.lightBackground) 0xCCFFFFFF.toInt() else 0xD9000000.toInt()

        textPaint.textSize = size
        textPaint.color = ink
        originalPaint.textSize = size * 0.66f
        originalPaint.color = if (item.lightBackground) 0x99111111.toInt() else 0x99FFFFFF.toInt()

        val contentHeight = layout.height +
            if (showSource) 2f * density + originalPaint.textSize * 1.25f else 0f
        var top = box.centerY() - contentHeight / 2f
        if (top < box.top) top = box.top
        if (top + contentHeight > box.bottom) top = box.bottom - contentHeight

        canvas.save()
        canvas.clipRect(box)
        canvas.translate(box.left, top)

        // Contour first, then the fill, so light text stays readable on artwork.
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = (size * 0.085f).coerceAtLeast(0.7f * density)
        textPaint.strokeJoin = Paint.Join.ROUND
        textPaint.color = contour
        layout.draw(canvas)
        textPaint.style = Paint.Style.FILL
        textPaint.color = ink
        layout.draw(canvas)

        if (showSource) {
            val source = layoutOf(item.original, originalPaint.textSize, box.width(), originalPaint)
            canvas.translate(0f, layout.height + 2f * density)
            source.draw(canvas)
        }
        canvas.restore()
    }

    private fun layoutOf(text: String, size: Float, width: Float, paint: TextPaint): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.06f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
