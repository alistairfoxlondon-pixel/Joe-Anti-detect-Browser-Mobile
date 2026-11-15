package com.joebrowser.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.joebrowser.app.core.Async
import org.json.JSONObject

/**
 * Local-only SQLite storage.
 *
 * Design constraints:
 *  - Every public method is synchronous and therefore **must** be called from a background
 *    thread. [ProfileRepository] is the only sanctioned caller from UI code and it always
 *    goes through [com.joebrowser.app.core.Async].
 *  - Rows are stored as JSON documents with a few projection columns for ordering and
 *    filtering. A row that fails to parse is skipped, never fatal.
 *  - [onUpgrade] migrates forward one version at a time and is idempotent, so a partially
 *    applied upgrade cannot wedge the app.
 */
class Database private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL keeps UI reads from blocking on the monitor service's writes.
        runCatching { db.enableWriteAheadLogging() }
        runCatching { db.setForeignKeyConstraintsEnabled(true) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE profiles(
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                group_name TEXT NOT NULL DEFAULT '',
                json TEXT NOT NULL,
                created INTEGER NOT NULL,
                last_opened INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE proxies(
                id TEXT PRIMARY KEY NOT NULL,
                json TEXT NOT NULL,
                host TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'none',
                watch INTEGER NOT NULL DEFAULT 0,
                last_check INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE TABLE extensions(id TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL)")
        db.execSQL(
            """CREATE TABLE history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                profile_id TEXT NOT NULL,
                profile_name TEXT NOT NULL,
                url TEXT NOT NULL,
                title TEXT NOT NULL,
                ts INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_history_ts ON history(ts DESC)")
        db.execSQL("CREATE INDEX idx_history_profile ON history(profile_id)")
        db.execSQL(
            """CREATE TABLE watch_state(
                key TEXT PRIMARY KEY NOT NULL,
                ip TEXT NOT NULL DEFAULT '',
                fails INTEGER NOT NULL DEFAULT 0,
                notified_off INTEGER NOT NULL DEFAULT 0,
                last_check INTEGER NOT NULL DEFAULT 0,
                latency INTEGER NOT NULL DEFAULT -1
            )"""
        )
        db.execSQL("CREATE TABLE trash(id TEXT PRIMARY KEY NOT NULL, json TEXT NOT NULL, deleted_at INTEGER NOT NULL)")
        db.execSQL(
            """CREATE TABLE alerts(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                target TEXT NOT NULL,
                detail TEXT NOT NULL,
                ts INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_alerts_ts ON alerts(ts DESC)")
        db.execSQL("CREATE TABLE monitor_status(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, updated INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 -> v2: monitor alerts, richer watch state, monitor status cache.
        if (oldVersion < 2) {
            runCatching {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS alerts(
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        kind TEXT NOT NULL,
                        target TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        ts INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_alerts_ts ON alerts(ts DESC)")
                db.execSQL("CREATE TABLE IF NOT EXISTS monitor_status(key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL, updated INTEGER NOT NULL)")
            }.onFailure { Log.w(Async.TAG, "Migration 1->2 partially applied", it) }
            runCatching { db.execSQL("ALTER TABLE watch_state ADD COLUMN latency INTEGER NOT NULL DEFAULT -1") }
        }
    }

    // ------------------------------------------------------------- profiles

    fun saveProfile(p: Profile) {
        val values = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("group_name", p.group)
            put("json", p.toJson(includeSecret = true).toString())
            put("created", p.created)
            put("last_opened", p.lastOpened)
        }
        writableDatabase.insertWithOnConflict("profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProfile(id: String): Profile? = try {
        readableDatabase.query("profiles", arrayOf("json"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) Profile.fromJson(JSONObject(c.getString(0))) else null
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "getProfile failed", t)
        null
    }

    fun getAllProfiles(): List<Profile> = queryJson("profiles", "last_opened DESC, created DESC") { Profile.fromJson(it) }

    fun countProfiles(): Int = count("profiles", null, null)

    fun deleteProfile(p: Profile, keepTrash: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (keepTrash) {
                val values = ContentValues().apply {
                    put("id", p.id)
                    put("json", p.toJson(includeSecret = true).toString())
                    put("deleted_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("trash", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.delete("profiles", "id = ?", arrayOf(p.id))
            db.delete("history", "profile_id = ?", arrayOf(p.id))
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
        }
    }

    fun trashCount(): Int = count("trash", null, null)

    fun getAllTrashedProfiles(): List<Pair<Profile, Long>> {
        val out = mutableListOf<Pair<Profile, Long>>()
        try {
            readableDatabase.query(
                "trash",
                arrayOf("json", "deleted_at"),
                null,
                null,
                null,
                null,
                "deleted_at DESC"
            ).use { c ->
                while (c.moveToNext()) {
                    runCatching {
                        val profile = Profile.fromJson(JSONObject(c.getString(0)))
                        val deletedAt = c.getLong(1)
                        out.add(profile to deletedAt)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(Async.TAG, "getAllTrashedProfiles failed", t)
        }
        return out
    }

    fun restoreTrashedProfile(id: String): Profile? {
        val db = writableDatabase
        var restored: Profile? = null
        db.beginTransaction()
        try {
            db.query("trash", arrayOf("json"), "id = ?", arrayOf(id), null, null, null).use { c ->
                if (c.moveToFirst()) {
                    restored = Profile.fromJson(JSONObject(c.getString(0)))
                }
            }
            val p = restored
            if (p != null) {
                val values = ContentValues().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("group_name", p.group)
                    put("json", p.toJson(includeSecret = true).toString())
                    put("created", p.created)
                    put("last_opened", p.lastOpened)
                }
                db.insertWithOnConflict("profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                db.delete("trash", "id = ?", arrayOf(id))
            }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
        }
        return restored
    }

    fun deleteTrashedForever(id: String) {
        writableDatabase.delete("trash", "id = ?", arrayOf(id))
    }

    fun emptyTrash() {
        writableDatabase.delete("trash", null, null)
    }

    // -------------------------------------------------------------- proxies

    fun saveProxy(p: SavedProxy) {
        val values = ContentValues().apply {
            put("id", p.id)
            put("json", p.toJson(includeSecret = true).toString())
            put("host", p.config.host)
            put("type", p.config.type)
            put("watch", if (p.config.watch) 1 else 0)
            put("last_check", p.config.lastCheckedAt)
        }
        writableDatabase.insertWithOnConflict("proxies", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllProxies(): List<SavedProxy> =
        queryJson("proxies", "last_check DESC, host ASC") { SavedProxy.fromJson(it) }

    fun getProxy(id: String): SavedProxy? = try {
        readableDatabase.query("proxies", arrayOf("json"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) SavedProxy.fromJson(JSONObject(c.getString(0))) else null
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "getProxy failed", t)
        null
    }

    fun countWatchedProxies(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM proxies WHERE watch = 1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (t: Throwable) {
        0
    }

    fun deleteProxy(id: String) {
        writableDatabase.delete("proxies", "id = ?", arrayOf(id))
    }

    // ----------------------------------------------------------- extensions

    fun saveExtension(e: ExtensionItem) {
        val values = ContentValues().apply {
            put("id", e.id)
            put("json", e.toJson().toString())
        }
        writableDatabase.insertWithOnConflict("extensions", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllExtensions(): List<ExtensionItem> = try {
        readableDatabase.query("extensions", arrayOf("json"), null, null, null, null, null).use { c ->
            val out = ArrayList<ExtensionItem>()
            while (c.moveToNext()) {
                val item = ExtensionItem.fromJson(
                    runCatching { JSONObject(c.getString(0)) }.getOrNull()
                )
                if (item != null) out.add(item)
            }
            out
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "getAllExtensions failed", t)
        emptyList()
    }

    fun deleteExtension(id: String) {
        writableDatabase.delete("extensions", "id = ?", arrayOf(id))
    }

    // -------------------------------------------------------------- history

    fun addHistory(profileId: String, profileName: String, url: String, title: String) {
        val values = ContentValues().apply {
            put("profile_id", profileId)
            put("profile_name", profileName)
            put("url", url)
            put("title", title)
            put("ts", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insert("history", null, values)
        // Bound storage: keep the newest 1000 visits per profile.
        db.execSQL(
            """DELETE FROM history WHERE profile_id = ? AND id NOT IN (
                SELECT id FROM history WHERE profile_id = ? ORDER BY ts DESC LIMIT 1000)""",
            arrayOf(profileId, profileId)
        )
    }

    fun getHistory(limit: Int = 500, profileId: String? = null): List<HistoryEntry> = try {
        val selection = if (profileId != null) "profile_id = ?" else null
        val args = if (profileId != null) arrayOf(profileId) else null
        readableDatabase.query("history", null, selection, args, null, null, "ts DESC", limit.toString()).use { c ->
            val out = ArrayList<HistoryEntry>()
            while (c.moveToNext()) {
                out.add(
                    HistoryEntry(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        profileName = c.getString(c.getColumnIndexOrThrow("profile_name")),
                        url = c.getString(c.getColumnIndexOrThrow("url")),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts"))
                    )
                )
            }
            out
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "getHistory failed", t)
        emptyList()
    }

    /** Removes a single history row (long-press → Delete in the history list). */
    fun deleteHistory(id: Long) {
        try {
            writableDatabase.delete("history", "id = ?", arrayOf(id.toString()))
        } catch (t: Throwable) {
            Log.w(Async.TAG, "deleteHistory failed", t)
        }
    }

    fun clearHistory(profileId: String? = null) {
        if (profileId == null) writableDatabase.delete("history", null, null)
        else writableDatabase.delete("history", "profile_id = ?", arrayOf(profileId))
    }

    // --------------------------------------------------------- watch state

    data class WatchState(
        val ip: String,
        val fails: Int,
        val notifiedOff: Boolean,
        val latency: Int,
        val lastCheck: Long
    )

    fun getWatchState(key: String): WatchState? = try {
        readableDatabase.query("watch_state", null, "key = ?", arrayOf(key), null, null, null).use { c ->
            if (!c.moveToFirst()) null
            else WatchState(
                ip = c.getString(c.getColumnIndexOrThrow("ip")),
                fails = c.getInt(c.getColumnIndexOrThrow("fails")),
                notifiedOff = c.getInt(c.getColumnIndexOrThrow("notified_off")) == 1,
                latency = c.getInt(c.getColumnIndexOrThrow("latency")),
                lastCheck = c.getLong(c.getColumnIndexOrThrow("last_check"))
            )
        }
    } catch (t: Throwable) {
        null
    }

    fun setWatchState(key: String, ip: String, fails: Int, notifiedOff: Boolean, latency: Int = -1) {
        val values = ContentValues().apply {
            put("key", key)
            put("ip", ip)
            put("fails", fails)
            put("notified_off", if (notifiedOff) 1 else 0)
            put("last_check", System.currentTimeMillis())
            put("latency", latency)
        }
        writableDatabase.insertWithOnConflict("watch_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // --------------------------------------------------------------- alerts

    fun addAlert(alert: MonitorAlert): Long = try {
        val values = ContentValues().apply {
            put("kind", alert.kind)
            put("target", alert.target)
            put("detail", alert.detail)
            put("ts", alert.ts)
        }
        val rowId = writableDatabase.insert("alerts", null, values)
        // Bound the alert log so the table cannot grow without limit.
        writableDatabase.execSQL("DELETE FROM alerts WHERE id NOT IN (SELECT id FROM alerts ORDER BY ts DESC LIMIT 200)")
        rowId
    } catch (t: Throwable) {
        Log.w(Async.TAG, "addAlert failed", t)
        -1L
    }

    /** Counts the alert log without materialising rows (Home's metric card). */
    fun alertCount(): Int = try {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM alerts", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "alertCount failed", t)
        0
    }

    fun getAlerts(limit: Int = 40): List<MonitorAlert> = try {
        readableDatabase.query("alerts", null, null, null, null, null, "ts DESC", limit.toString()).use { c ->
            val out = ArrayList<MonitorAlert>()
            while (c.moveToNext()) {
                out.add(
                    MonitorAlert(
                        id = c.getLong(c.getColumnIndexOrThrow("id")),
                        kind = c.getString(c.getColumnIndexOrThrow("kind")),
                        target = c.getString(c.getColumnIndexOrThrow("target")),
                        detail = c.getString(c.getColumnIndexOrThrow("detail")),
                        ts = c.getLong(c.getColumnIndexOrThrow("ts"))
                    )
                )
            }
            out
        }
    } catch (t: Throwable) {
        emptyList()
    }

    fun clearAlerts() {
        runCatching { writableDatabase.delete("alerts", null, null) }
    }

    // ------------------------------------------------------- monitor status

    fun putStatus(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated", System.currentTimeMillis())
        }
        runCatching {
            writableDatabase.insertWithOnConflict("monitor_status", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun getStatus(key: String): String? = try {
        readableDatabase.query("monitor_status", arrayOf("value"), "key = ?", arrayOf(key), null, null, null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) {
        null
    }

    // --------------------------------------------------------------- helpers

    private fun count(table: String, selection: String?, args: Array<String>?): Int = try {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM " + table + (selection?.let { " WHERE $it" } ?: ""),
            args
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    } catch (t: Throwable) {
        0
    }

    /** Reads a JSON column from every row, skipping rows that fail to parse. */
    private fun <T> queryJson(table: String, orderBy: String?, parse: (JSONObject) -> T): List<T> = try {
        readableDatabase.query(table, arrayOf("json"), null, null, null, null, orderBy).use { c: Cursor ->
            val out = ArrayList<T>()
            while (c.moveToNext()) {
                val row = runCatching { JSONObject(c.getString(0)) }.getOrNull()
                if (row != null) {
                    runCatching { parse(row) }.onSuccess { out.add(it) }
                        .onFailure { Log.w(Async.TAG, "Skipping unparseable row in $table", it) }
                }
            }
            out
        }
    } catch (t: Throwable) {
        Log.w(Async.TAG, "Query on $table failed", t)
        emptyList()
    }

    /** Wipes every profile-scoped table. Used by "Reset application data". */
    fun wipeAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            listOf("profiles", "proxies", "extensions", "history", "trash", "alerts", "watch_state", "monitor_status")
                .forEach { runCatching { db.delete(it, null, null) } }
            db.setTransactionSuccessful()
        } finally {
            runCatching { db.endTransaction() }
        }
    }

    companion object {
        private const val NAME = "joe_browser.db"
        /** v2 adds monitor alerts + latency in watch state. */
        private const val VERSION = 2

        @Volatile
        private var INSTANCE: Database? = null

        fun get(context: Context): Database = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Database(context.applicationContext).also { INSTANCE = it }
        }

        /** Closes the handle (used when the user resets all data). */
        fun closeInstance() {
            synchronized(this) {
                runCatching { INSTANCE?.close() }
                INSTANCE = null
            }
        }
    }
}
