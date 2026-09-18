package com.bello.assistant.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.bello.assistant.core.FileLog

/**
 * Everything Bello keeps between restarts, in one small SQLite file: the facts the user asked it
 * to remember (FR-MEM-03/05) and the timers and alarms still to ring (FR-TOOL-02/03).
 * It never leaves the tablet.
 */
class BelloDb(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "text TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE schedules (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "kind TEXT NOT NULL, due_at INTEGER NOT NULL, label TEXT, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)"
        )
        FileLog.i(TAG, "database created")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Nothing to migrate yet; memories are cheap to lose compared to a broken start.
        db.execSQL("DROP TABLE IF EXISTS facts")
        db.execSQL("DROP TABLE IF EXISTS schedules")
        onCreate(db)
    }

    fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is Long -> put(key, value)
                is Int -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }

    private companion object {
        const val TAG = "db"
        const val NAME = "bello.db"
        const val VERSION = 1
    }
}
