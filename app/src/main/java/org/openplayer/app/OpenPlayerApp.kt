package org.openplayer.app

import android.app.Application

class OpenPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Tx.init(this)
        ThemeManager.install(this)
        BugReport.install(this)
    }
}
