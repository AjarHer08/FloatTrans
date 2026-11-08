package com.floattrans.app

import android.app.Application
import com.floattrans.app.core.Prefs

class FloatTransApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }
}
