package com.joebrowser.app

import android.app.Application
import android.util.Log
import com.joebrowser.app.core.Async
import com.joebrowser.app.data.Database
import com.joebrowser.app.data.Prefs
import com.joebrowser.app.data.ProfileStorage
import com.joebrowser.app.proxy.ProxyMonitorService
import com.joebrowser.app.proxy.ProxyNotifier

/**
 * Application entry point.
 *
 * Startup is deliberately minimal and entirely off the UI thread:
 *  - theme is applied before any activity inflates (so there is no light-flash in dark mode),
 *  - notification channels are created once,
 *  - the database is opened and profile directories are recreated on a background thread, so
 *    the first frame is never blocked by disk I/O,
 *  - the proxy monitor is re-armed if the user had watched proxies.
 */
class JoeBrowserApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()

        // Theme first: AppCompat reads this when the first activity is created.
        runCatching { Prefs.applyTheme(Prefs.themeMode(this)) }
            .onFailure { Log.w(Async.TAG, "Could not apply theme at startup", it) }

        runCatching { ProxyNotifier.ensureChannels(this) }

        // Warm the database and re-create per-profile directories away from the UI thread.
        Async.serialize {
            runCatching { Database.get(this).readableDatabase }
                .onFailure { Log.e(Async.TAG, "Database open failed at startup", it) }
            runCatching {
                Database.get(this).getAllProfiles().forEach { ProfileStorage.ensure(this, it.id) }
            }.onFailure { Log.w(Async.TAG, "Profile directory warm-up failed", it) }
        }

        // Re-arm the monitor if proxies are watched (no-op otherwise, and safe if restricted).
        runCatching { ProxyMonitorService.startIfNeeded(this) }
            .onFailure { Log.w(Async.TAG, "Monitor start deferred", it) }
    }

    /**
     * Last-resort crash reporter: an uncaught exception is written to a private log file
     * (`crash-last.txt`) before the default handler runs, so a field crash can be diagnosed
     * from the device without any analytics SDK and without the network. Nothing leaves the
     * device; the file is overwritten on the next crash; it is app-private and never uploaded.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                filesDir.resolve("crash-last.txt").writeText(
                    buildString {
                        append("time=").append(stamp).append('\n')
                        append("thread=").append(thread.name).append(" (id=").append(thread.id).append(")\n")
                        append("appVersion=")
                        .append(runCatching {
                            packageManager.getPackageInfo(packageName, 0).versionName
                        }.getOrDefault("unknown"))
                        .append("\n\n")
                        append(android.util.Log.getStackTraceString(throwable))
                    }
                )
            }.onFailure { Log.w(Async.TAG, "Could not persist crash log", it) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        runCatching { Database.closeInstance() }
        super.onTerminate()
    }

    companion object {
        @Volatile
        lateinit var instance: JoeBrowserApp
            private set

        fun app(): JoeBrowserApp? = if (::instance.isInitialized) instance else null
    }
}
