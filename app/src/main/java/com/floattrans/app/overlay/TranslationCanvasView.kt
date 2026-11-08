package com.floattrans.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import com.floattrans.app.core.Prefs
import kotlin.math.max

/**
 * Draws translated lines directly on top of the boxes the OCR engine found,
 * scaled from screenshot coordinates to overlay coordinates.
 */
class TranslationCanvasView(context: Context) : View(context) {

    data class Item(
        val translated: String,
        val original: String,
        /** Bounding box in captured-screenshot coordinates. */
        val box: RectF
    )

    private val items = mutableListOf<Item>()
    private var captureWidth = 1
    private var captureHeight = 1

    private var textSizeSp = 14f
    private var style = Prefs.STYLE_SOLID
    private var showOriginal = false

    private val density = resources.displayMetrics.density
    private val spScale =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        isSubpixelText = true
    }
    private val originalPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xF01A1B1E.toInt()
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0x88FFFFFF.toInt()
    }

    init {
        backgroundPaint.style = Paint.Style.FILL
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
        captureWidth = max(1, capWidth)
        captureHeight = max(1, capHeight)
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
        val pad = 3f * density
        val radius = 5f * density

        for (item in items) {
            val box = RectF(
                item.box.left * scaleX - pad,
                item.box.top * scaleY - pad,
                item.box.right * scaleX + pad,
                item.box.bottom * scaleY + pad
            )
            box.left = box.left.coerceIn(0f, width.toFloat())
            box.right = box.right.coerceIn(0f, width.toFloat())
            box.top = box.top.coerceIn(0f, height.toFloat())
            box.bottom = box.bottom.coerceIn(0f, height.toFloat())
            if (box.width() < 12f || box.height() < 10f) continue

            drawItem(canvas, item, box, radius)
        }
    }

    private fun drawItem(canvas: Canvas, item: Item, box: RectF, radius: Float) {
        val inner = 3f * density
        val maxWidth = max(24f, box.width() - inner * 2)
        val maxHeight = max(16f, box.height() - inner * 2)

        var size = textSizeSp * spScale
        var translated = buildLayout(item.translated, size, maxWidth)
        var original = if (showOriginal) buildLayout(item.original, size * 0.68f, maxWidth) else null
        var guard = 0
        val minSize = 7f * density

        fun totalHeight(): Float =
            translated.height.toFloat() + (original?.let { it.height + 2f * density } ?: 0f)

        fun fits(): Boolean =
            totalHeight() <= maxHeight && translated.width <= maxWidth + 1f

        while (guard++ < 16 && size > minSize && !fits()) {
            size *= 0.87f
            translated = buildLayout(item.translated, size, maxWidth)
            original = if (showOriginal) buildLayout(item.original, size * 0.68f, maxWidth) else null
        }

        when (style) {
            Prefs.STYLE_SOLID -> canvas.drawRoundRect(box, radius, radius, backgroundPaint)
            Prefs.STYLE_OUTLINE -> {
                canvas.drawRoundRect(box, radius, radius, backgroundPaint)
                canvas.drawRoundRect(box, radius, radius, strokePaint)
            }
            else -> Unit
        }

        textPaint.textSize = size
        textPaint.setShadowLayer(4f * density, 0f, 0f, 0xB3000000.toInt())
        originalPaint.textSize = size * 0.68f
        originalPaint.setShadowLayer(3f * density, 0f, 0f, 0xB3000000.toInt())

        val contentHeight = totalHeight()
        var y = box.centerY() - contentHeight / 2f

        canvas.save()
        canvas.translate(box.centerX() - translated.width / 2f, y)
        translated.draw(canvas)
        canvas.restore()

        original?.let {
            y += translated.height + 2f * density
            canvas.save()
            canvas.translate(box.centerX() - it.width / 2f, y)
            it.draw(canvas)
            canvas.restore()
        }
    }

    private fun buildLayout(text: String, size: Float, width: Float): StaticLayout {
        textPaint.textSize = size
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width.toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
        return builder.build()
    }
}
