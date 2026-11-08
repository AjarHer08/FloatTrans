package com.floattrans.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/** Small floating card with the quick actions of the running session. */
class ControlPanel(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onTranslateNow()
        fun onToggleAuto()
        fun onClear()
        fun onOpenSettings()
        fun onStop()
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val mainHandler = Handler(Looper.getMainLooper())

    private var attached = false
    private val autoRow: TextView
    private val card: LinearLayout

    init {
        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(0xF2191A1D.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0x33FFFFFF)
            }
            setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
        }

        card.addView(title("FloatTrans"))

        autoRow = row("Mode otomatis", dismiss = false) { callbacks.onToggleAuto() }
        card.addView(row("Terjemahkan sekarang") { callbacks.onTranslateNow() })
        card.addView(autoRow)
        card.addView(row("Bersihkan terjemahan") { callbacks.onClear() })
        card.addView(row("Buka pengaturan") { callbacks.onOpenSettings() })
        card.addView(row("Hentikan sesi") { callbacks.onStop() })
    }

    private val params = WindowManager.LayoutParams(
        (210 * density).toInt(),
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        },
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.CENTER
    }

    fun show(autoEnabled: Boolean) {
        val wasAttached = attached
        if (!attached) {
            runCatching {
                windowManager.addView(card, params)
                attached = true
            }
        }
        if (!wasAttached) {
            updateAuto(autoEnabled)
            mainHandler.removeCallbacksAndMessages(null)
            mainHandler.postDelayed({ hide() }, AUTO_HIDE_MS)
        }
    }

    fun updateAuto(enabled: Boolean) {
        autoRow.text = if (enabled) "Mode otomatis: AKTIF" else "Mode otomatis: MATI"
        autoRow.setTextColor(if (enabled) 0xFF4ADE80.toInt() else 0xFFFFFFFF.toInt())
    }

    fun hide() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!attached) return
        runCatching { windowManager.removeView(card) }
        attached = false
    }

    val isAttached: Boolean get() = attached

    private fun title(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFF9AA0A6.toInt())
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
    }

    private fun row(text: String, dismiss: Boolean = true, onClick: () -> Unit): TextView = TextView(context).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(
            (16 * density).toInt(),
            (11 * density).toInt(),
            (16 * density).toInt(),
            (11 * density).toInt()
        )
        isClickable = true
        isFocusable = true
        setOnClickListener {
            onClick()
            if (dismiss) hide() else mainHandler.postDelayed({ hide() }, AUTO_HIDE_MS)
        }
    }

    private companion object {
        const val AUTO_HIDE_MS = 12_000L
    }
}
