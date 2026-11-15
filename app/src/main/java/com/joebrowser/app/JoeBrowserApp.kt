package com.joebrowser.app

import android.app.Application
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.proxy.ProxyNotifier

class JoeBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        ProxyNotifier.ensureChannel(this)
        Database.get(this) // open + migrate eagerly so the first screen is not blocked
        Prefs.applyTheme(Prefs.themeMode(this))
    }

    companion object {
        @Volatile
        lateinit var instance: JoeBrowserApp
            private set
    }
}
