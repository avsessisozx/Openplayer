package org.openplayer.app

import android.app.Application

class OpenPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.install(this)
        BugReport.install(this)
    }
}
