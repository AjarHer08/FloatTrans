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
 * typesetting rather than like a UI label.
 *
 * Two passes are used on purpose: first every balloon gets an opaque patch of
 * its own background colour (so no original glyph is left peeking through),
 * then all translations are drawn on top. Otherwise a patch drawn later could
 * cover a translation drawn earlier when two balloons are close together.
 */
class TranslationCanvasView(context: Context) : View(context) {

    data class Item(
        val translated: String,
        val original: String,
        /** Bounding box of the bubble lettering in captured-screenshot coordinates. */
        val box: RectF,
        /** True when the bubble behind the text is light. */
        val lightBackground: Boolean = false,
        /** Dominant colour of the bubble, used to paint over the original text. */
        val background: Int = Color.BLACK
    )

    private class Frame(val item: Item, val box: RectF)

    private val items = mutableListOf<Item>()
    private var captureWidth = 1
    private var captureHeight = 1

    private var textSizeSp = 14f
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

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

        val frames = ArrayList<Frame>(items.size)
        for (item in items) {
            val raw = RectF(
                item.box.left * scaleX,
                item.box.top * scaleY,
                item.box.right * scaleX,
                item.box.bottom * scaleY
            )
            if (raw.width() < 6f || raw.height() < 6f) continue
            val box = expanded(raw)
            if (box.width() < 8f || box.height() < 8f) continue
            frames.add(Frame(item, box))
        }
        if (frames.isEmpty()) return

        for (frame in frames) paintCover(canvas, frame.item, frame.box)
        for (frame in frames) drawText(canvas, frame.item, frame.box)
    }

    /**
     * OCR boxes hug the glyphs while balloons have breathing room. The patch is
     * grown a little so that wrapped lines we did not recognise — or glyph edges
     * clipped by the OCR box — are covered as well.
     */
    private fun expanded(raw: RectF): RectF {
        val padX = (raw.width() * 0.07f).coerceIn(2f * density, 10f * density)
        val padY = (raw.height() * 0.22f).coerceIn(3f * density, 12f * density)
        return RectF(
            (raw.left - padX).coerceAtLeast(0f),
            (raw.top - padY).coerceAtLeast(0f),
            (raw.right + padX).coerceAtMost(width.toFloat()),
            (raw.bottom + padY).coerceAtMost(height.toFloat())
        )
    }

    private fun paintCover(canvas: Canvas, item: Item, box: RectF) {
        if (style == Prefs.STYLE_NONE) return
        val radius = min(box.height() / 2.4f, 16f * density)
        fillPaint.color = withAlpha(item.background, 255)
        canvas.drawRoundRect(box, radius, radius, fillPaint)
        if (style == Prefs.STYLE_OUTLINE) {
            borderPaint.color = if (item.lightBackground) 0x33000000 else 0x66FFFFFF
            canvas.drawRoundRect(box, radius, radius, borderPaint)
        }
    }

    private fun drawText(canvas: Canvas, item: Item, box: RectF) {
        val showSource = showOriginal && item.original.isNotBlank() &&
            !item.original.equals(item.translated, ignoreCase = true)

        val inner = RectF(box).apply { inset(2f * density, 1.5f * density) }
        val textArea = if (showSource) inner.height() * 0.78f else inner.height()

        val preferred = min(inner.height(), inner.width() * 0.62f)
            .coerceAtMost(textSizeSp * spScale * 2.2f)
            .coerceAtLeast(8f * density)

        val availableWidth = inner.width().toInt().coerceAtLeast(1)
        var size = preferred
        var layout = layoutOf(item.translated, size, availableWidth, textPaint)
        var guard = 0
        while (guard++ < 24 && layout.height > textArea) {
            size *= 0.85f
            if (size < 6f * density) break
            layout = layoutOf(item.translated, size, availableWidth, textPaint)
        }

        val darkBackground = !item.lightBackground
        val ink = if (item.lightBackground) 0xFF111111.toInt() else 0xFFFFFFFF.toInt()
        val contour = when {
            style == Prefs.STYLE_NONE || darkBackground -> 0xE6000000.toInt()
            else -> 0x00000000
        }

        textPaint.textSize = size
        textPaint.color = ink

        val contentHeight = layout.height +
            if (showSource) 2f * density + size * 0.66f * 1.25f else 0f
        var top = inner.centerY() - contentHeight / 2f
        if (top < inner.top) top = inner.top
        if (top + contentHeight > inner.bottom) top = inner.bottom - contentHeight

        canvas.save()
        canvas.clipRect(inner)
        canvas.translate(inner.left, top)

        if (contour != 0) {
            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = (size * 0.09f).coerceAtLeast(0.7f * density)
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.color = contour
            layout.draw(canvas)
        }
        textPaint.style = Paint.Style.FILL
        textPaint.color = ink
        layout.draw(canvas)

        if (showSource) {
            val sourceSize = size * 0.66f
            originalPaint.textSize = sourceSize
            originalPaint.color =
                if (item.lightBackground) 0x99111111.toInt() else 0x99FFFFFF.toInt()
            val source = layoutOf(item.original, sourceSize, availableWidth, originalPaint)
            canvas.translate(0f, layout.height + 2f * density)
            source.draw(canvas)
        }
        canvas.restore()
    }

    private fun layoutOf(text: String, size: Float, width: Int, paint: TextPaint): StaticLayout {
        paint.textSize = size
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
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
