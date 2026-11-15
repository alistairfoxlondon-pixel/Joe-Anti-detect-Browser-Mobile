package com.joebrowser.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central threading + error-boundary utility.
 *
 * Rules enforced by this file:
 *  - No database, disk or socket work ever runs on the UI thread (see [bg] / [compute]).
 *  - Every background unit of work is wrapped in a try/catch boundary so a failure in one
 *    subsystem (a malformed proxy line, a socket timeout, a JSON blob written by an older
 *    build) can never take down the process.
 *  - Every UI callback is posted to the main looper and is itself guarded, so a rendering
 *    bug degrades into a log line instead of a crash.
 */
object Async {

    const val TAG = "JoeBrowser"

    private val threadIds = AtomicInteger(1)

    private val threadFactory = ThreadFactory { runnable ->
        Thread(runnable, "joe-bg-${threadIds.getAndIncrement()}").apply { isDaemon = true }
    }

    /** Small fixed pool: enough for parallel proxy checks without starving the device. */
    private val pool: ExecutorService = Executors.newFixedThreadPool(3, threadFactory)

    /** Serial executor for ordered writes (profile/proxy mutations must not interleave). */
    private val writes: ExecutorService = Executors.newSingleThreadExecutor(threadFactory)

    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ threads

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    /** Runs [block] on the main thread, immediately when already there. */
    fun onMain(block: () -> Unit) {
        if (isMainThread()) guard(block) else mainHandler.post { guard(block) }
    }

    /** Runs [block] on the background pool. Never throws; failures are logged. */
    fun bg(block: () -> Unit) {
        pool.execute { guard(block) }
    }

    /** Runs [block] on the serial background writer. Never throws; failures are logged. */
    fun serialize(block: () -> Unit) {
        writes.execute { guard(block) }
    }

    /**
     * Computes [block] off the UI thread and delivers the result (or null on failure)
     * on the main thread. This is the only sanctioned way to touch SQLite from the UI.
     */
    fun <T> compute(block: () -> T, onResult: (T?) -> Unit) {
        pool.execute {
            val value = try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "Background task failed: ${t.javaClass.simpleName}", t)
                null
            }
            mainHandler.post {
                try {
                    onResult(value)
                } catch (t: Throwable) {
                    Log.e(TAG, "Result handler failed", t)
                }
            }
        }
    }

    /** Fire-and-forget compute where only success matters. */
    fun <T> computeIgnoreResult(block: () -> T) {
        pool.execute { runCatching { block() }.onFailure { Log.w(TAG, "Background task failed", it) } }
    }

    fun <T> T?.orDefault(fallback: T): T = this ?: fallback

    // ------------------------------------------------------------------ guards

    /** Executes [block] swallowing (and logging) any throwable. */
    inline fun guard(crossinline block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "Guarded block failed: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    /** Executes [block] and returns null when it throws. */
    inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        Log.w(TAG, "Attempt failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Clipboard write that never throws (some OEM builds throw on empty clips). */
    fun copyToClipboard(context: Context?, text: String, label: String = "Joe Browser") {
        if (context == null || text.isBlank()) return
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        }
    }

    fun toast(context: Context?, message: String) {
        if (context == null || message.isBlank()) return
        onMain {
            try {
                Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "Toast failed", t)
            }
        }
    }

    /** Wraps a UI action so a stray exception in a click handler cannot kill the app. */
    fun safe(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            Log.e(TAG, "UI action failed", t)
        }
    }
}

/** Turns an exception into something a human can read in a dialog or status line. */
object Errors {
    fun userMessage(t: Throwable?, fallback: String = "Something went wrong"): String {
        if (t == null) return fallback
        val raw = t.message?.trim().orEmpty()
        if (raw.isEmpty()) return "${fallback} (${t.javaClass.simpleName})"
        return when {
            raw.contains("ECONNREFUSED", true) || raw.contains("Connection refused", true) -> "Connection refused"
            raw.contains("timed out", true) || raw.contains("timeout", true) -> "Timed out"
            raw.contains("Unable to resolve host", true) || raw.contains("UnknownHost", true) -> "Host not found"
            raw.contains("SSL", true) || raw.contains("certificate", true) -> "TLS handshake failed"
            // Android's SocketException text is technical but contains the one fact the user
            // needs (the proxy refused or reset the connection), so it is translated, not dumped.
            raw.contains("failed to connect", true) || raw.contains("Connection reset", true) ->
                "Connection refused"
            raw.contains("Network is unreachable", true) || raw.contains("No route to host", true) ->
                "No route to host"
            raw.length > 120 -> raw.take(117) + "…"
            else -> raw
        }
    }
}
