package com.floattrans.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.floattrans.app.core.Prefs
import kotlin.math.abs
import kotlin.math.max

/** Draggable floating bubble: tap = translate now, long press = menu. */
class BubbleOverlay(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onTranslateNow()
        fun onOpenMenu()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val density = context.resources.displayMetrics.density
    private val sizePx = (54 * density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var attached = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private val background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xF21F2023.toInt())
        setStroke((1.5f * density).toInt().coerceAtLeast(1), 0x80FFFFFF.toInt())
    }

    private val bubble = TextView(context).apply {
        text = "文A"
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = this@BubbleOverlay.background
        elevation = 6f * density
    }

    private val params = WindowManager.LayoutParams(
        sizePx,
        sizePx,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = Prefs.bubbleX
        y = Prefs.bubbleY
        if (x < 0 || y < 0) {
            x = screenWidth() - sizePx - (10 * density).toInt()
            y = screenHeight() / 2
        }
    }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!dragging) callbacks.onTranslateNow()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!dragging) callbacks.onOpenMenu()
            }
        }
    )

    val isAttached: Boolean get() = attached

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (attached) return
        bubble.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, max(0, screenWidth() - sizePx))
                        params.y = (startY + dy.toInt()).coerceIn(0, max(0, screenHeight() - sizePx))
                        runCatching { windowManager.updateViewLayout(bubble, params) }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        Prefs.bubbleX = params.x
                        Prefs.bubbleY = params.y
                    }
                    dragging = false
                }
            }
            true
        }

        runCatching {
            windowManager.addView(bubble, params)
            attached = true
        }
    }

    fun hide() {
        if (!attached) return
        runCatching { windowManager.removeView(bubble) }
        attached = false
    }

    fun setWindowVisible(visible: Boolean) {
        bubble.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun setBusy(busy: Boolean) {
        background.setColor(if (busy) 0xF25B4BFF.toInt() else 0xF21F2023.toInt())
        bubble.text = if (busy) "…" else "文A"
    }

    fun setAutoMode(enabled: Boolean) {
        background.setStroke(
            (1.8f * density).toInt().coerceAtLeast(1),
            if (enabled) 0xFF4ADE80.toInt() else 0x80FFFFFF.toInt()
        )
    }

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels
}
