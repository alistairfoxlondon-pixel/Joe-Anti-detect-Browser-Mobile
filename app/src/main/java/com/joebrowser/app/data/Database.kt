package com.joebrowser.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * Local-only storage. No account, no cloud, no telemetry — profiles, saved proxies,
 * extensions and history live exclusively on this device.
 */
class Database private constructor(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

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
                host TEXT NOT NULL,
                type TEXT NOT NULL,
                watch INTEGER NOT NULL DEFAULT 0,
                last_check INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE extensions(
                id TEXT PRIMARY KEY NOT NULL,
                json TEXT NOT NULL
            )"""
        )
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
                last_check INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL(
            """CREATE TABLE trash(
                id TEXT PRIMARY KEY NOT NULL,
                json TEXT NOT NULL,
                deleted_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first release; future migrations go here.
    }

    // ---------------- Profiles ----------------

    fun saveProfile(p: Profile) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("name", p.name)
            put("group_name", p.group)
            put("json", p.toJson(includeSecret = true).toString())
            put("created", p.created)
            put("last_opened", p.lastOpened)
        }
        writableDatabase.insertWithOnConflict("profiles", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getProfile(id: String): Profile? =
        readableDatabase.query("profiles", arrayOf("json"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) Profile.fromJson(JSONObject(c.getString(0))) else null
        }

    fun getAllProfiles(): List<Profile> =
        readableDatabase.query("profiles", arrayOf("json"), null, null, null, null, "last_opened DESC, created DESC").use { c ->
            val out = ArrayList<Profile>()
            while (c.moveToNext()) {
                try { out.add(Profile.fromJson(JSONObject(c.getString(0)))) } catch (_: Exception) { }
            }
            out
        }

    fun countProfiles(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM profiles", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun deleteProfile(p: Profile, keepTrash: Boolean) {
        if (keepTrash) {
            val v = ContentValues().apply {
                put("id", p.id)
                put("json", p.toJson(includeSecret = true).toString())
                put("deleted_at", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("trash", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
        writableDatabase.delete("profiles", "id = ?", arrayOf(p.id))
        writableDatabase.delete("history", "profile_id = ?", arrayOf(p.id))
    }

    fun trashCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM trash", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun emptyTrash() {
        writableDatabase.delete("trash", null, null)
    }

    // ---------------- Saved proxies ----------------

    fun saveProxy(p: SavedProxy) {
        val v = ContentValues().apply {
            put("id", p.id)
            put("json", p.toJson(includeSecret = true).toString())
            put("host", p.config.host)
            put("type", p.config.type)
            put("watch", if (p.config.watch) 1 else 0)
            put("last_check", p.config.lastCheckedAt)
        }
        writableDatabase.insertWithOnConflict("proxies", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllProxies(): List<SavedProxy> =
        readableDatabase.query("proxies", arrayOf("json"), null, null, null, null, "last_check DESC").use { c ->
            val out = ArrayList<SavedProxy>()
            while (c.moveToNext()) {
                try { out.add(SavedProxy.fromJson(JSONObject(c.getString(0)))) } catch (_: Exception) { }
            }
            out
        }

    fun getProxy(id: String): SavedProxy? =
        readableDatabase.query("proxies", arrayOf("json"), "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) try { SavedProxy.fromJson(JSONObject(c.getString(0))) } catch (_: Exception) { null } else null
        }

    fun countWatchedProxies(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM proxies WHERE watch = 1", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    fun deleteProxy(id: String) {
        writableDatabase.delete("proxies", "id = ?", arrayOf(id))
    }

    // ---------------- Extensions ----------------

    fun saveExtension(e: ExtensionItem) {
        val v = ContentValues().apply {
            put("id", e.id)
            put("json", e.toJson().toString())
        }
        writableDatabase.insertWithOnConflict("extensions", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllExtensions(): List<ExtensionItem> =
        readableDatabase.query("extensions", arrayOf("json"), null, null, null, null, null).use { c ->
            val out = ArrayList<ExtensionItem>()
            while (c.moveToNext()) {
                try { out.add(ExtensionItem.fromJson(JSONObject(c.getString(0)))) } catch (_: Exception) { }
            }
            out
        }

    fun deleteExtension(id: String) {
        writableDatabase.delete("extensions", "id = ?", arrayOf(id))
    }

    // ---------------- History ----------------

    fun addHistory(profileId: String, profileName: String, url: String, title: String) {
        val v = ContentValues().apply {
            put("profile_id", profileId)
            put("profile_name", profileName)
            put("url", url)
            put("title", title)
            put("ts", System.currentTimeMillis())
        }
        val db = writableDatabase
        db.insert("history", null, v)
        // Cap history per profile at 1000 entries to bound storage.
        db.execSQL(
            """DELETE FROM history WHERE profile_id = ? AND id NOT IN (
                SELECT id FROM history WHERE profile_id = ? ORDER BY ts DESC LIMIT 1000)""",
            arrayOf(profileId, profileId)
        )
    }

    fun getHistory(limit: Int = 500): List<HistoryEntry> =
        readableDatabase.query("history", null, null, null, null, null, "ts DESC", limit.toString()).use { c ->
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

    fun clearHistory(profileId: String? = null) {
        if (profileId == null) writableDatabase.delete("history", null, null)
        else writableDatabase.delete("history", "profile_id = ?", arrayOf(profileId))
    }

    // ---------------- Proxy watch state ----------------

    data class WatchState(val ip: String, val fails: Int, val notifiedOff: Boolean)

    fun getWatchState(key: String): WatchState? =
        readableDatabase.query("watch_state", null, "key = ?", arrayOf(key), null, null, null).use { c ->
            if (!c.moveToFirst()) null
            else WatchState(
                ip = c.getString(c.getColumnIndexOrThrow("ip")),
                fails = c.getInt(c.getColumnIndexOrThrow("fails")),
                notifiedOff = c.getInt(c.getColumnIndexOrThrow("notified_off")) == 1
            )
        }

    fun setWatchState(key: String, ip: String, fails: Int, notifiedOff: Boolean) {
        val v = ContentValues().apply {
            put("key", key)
            put("ip", ip)
            put("fails", fails)
            put("notified_off", if (notifiedOff) 1 else 0)
            put("last_check", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("watch_state", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val NAME = "joe_browser.db"
        private const val VERSION = 1

        @Volatile
        private var INSTANCE: Database? = null

        fun get(context: Context): Database =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Database(context.applicationContext).also { INSTANCE = it }
            }
    }
}
