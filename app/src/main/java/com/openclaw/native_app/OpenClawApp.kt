package com.openclaw.native_app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenClawApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Application-level initialization handled by Hilt
    }
}
