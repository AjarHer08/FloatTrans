package com.floattrans.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.floattrans.app.core.Prefs

/** Full-screen, click-through window that paints the translated text. */
class TranslationOverlay(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    val view = TranslationCanvasView(context)

    private var attached = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        windowType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    val isAttached: Boolean get() = attached

    fun show() {
        if (attached) return
        runCatching {
            windowManager.addView(view, params)
            attached = true
        }
    }

    fun hide() {
        if (!attached) {
            view.visibility = View.VISIBLE
            return
        }
        view.visibility = View.VISIBLE
        runCatching { windowManager.removeView(view) }
        attached = false
    }

    /** Used while a screenshot is taken so the overlay is not captured itself. */
    fun setWindowVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun render(items: List<TranslationCanvasView.Item>, captureWidth: Int, captureHeight: Int) {
        view.configure(
            Prefs.textSizeSp.toFloat(),
            Prefs.backgroundStyle,
            Prefs.showOriginal
        )
        view.setItems(items, captureWidth, captureHeight)
    }

    fun clear() {
        view.clear()
    }

    private fun windowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
