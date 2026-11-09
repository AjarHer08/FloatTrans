package com.floattrans.app.core

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper around SharedPreferences. Call [init] once from Application. */
object Prefs {

    const val STYLE_AUTO = "auto"
    const val STYLE_SOLID = "solid"
    const val STYLE_OUTLINE = "outline"
    const val STYLE_NONE = "none"

    /** Online-first with offline fallback. */
    const val ENGINE_SMART = "smart"
    const val ENGINE_ONLINE = "online"
    const val ENGINE_OFFLINE = "offline"

    private const val FILE = "floattrans_prefs"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun ensure() {
        check(::sp.isInitialized) { "Prefs.init() was not called" }
    }

    var ocrScript: String
        get() = sp.getString("ocr_script", OcrScript.AUTO) ?: OcrScript.AUTO
        set(value) = sp.edit().putString("ocr_script", value).apply()

    var sourceLang: String
        get() = sp.getString("source_lang", Languages.AUTO) ?: Languages.AUTO
        set(value) = sp.edit().putString("source_lang", value).apply()

    var targetLang: String
        get() = sp.getString("target_lang", "id") ?: "id"
        set(value) = sp.edit().putString("target_lang", value).apply()

    /** How often the page watcher fingerprints the screen looking for a new page. */
    var intervalMs: Int
        get() = sp.getInt("interval_ms", 700)
        set(value) = sp.edit().putInt("interval_ms", value).apply()

    var textSizeSp: Int
        get() = sp.getInt("text_size_sp", 14)
        set(value) = sp.edit().putInt("text_size_sp", value).apply()

    var backgroundStyle: String
        get() = sp.getString("bg_style", STYLE_AUTO) ?: STYLE_AUTO
        set(value) = sp.edit().putString("bg_style", value).apply()

    var showOriginal: Boolean
        get() = sp.getBoolean("show_original", false)
        set(value) = sp.edit().putBoolean("show_original", value).apply()

    var translationEngine: String
        get() = sp.getString("translation_engine", ENGINE_SMART) ?: ENGINE_SMART
        set(value) = sp.edit().putString("translation_engine", value).apply()

    var autoMode: Boolean
        get() = sp.getBoolean("auto_mode", true)
        set(value) = sp.edit().putBoolean("auto_mode", value).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(value) = sp.edit().putInt("bubble_x", value).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(value) = sp.edit().putInt("bubble_y", value).apply()
}
