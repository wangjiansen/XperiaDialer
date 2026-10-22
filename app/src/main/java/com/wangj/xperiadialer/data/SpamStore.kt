package com.wangj.xperiadialer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 用户自建的号码标记库（黑名单 / 白名单）。
 *
 * 国内没有可用的开源骚扰号码库，手动标记是唯一贴合个人来电画像的数据来源。
 * 用 SQLite 而不是文件：需要按号码精确查、按标记类型列举，SQL 索引最省事。
 */
object SpamStore {

    /** mark 字段取值 */
    const val MARK_WHITE_LABEL = 1
    const val MARK_BLACK_LABEL = 2

    data class Marked(
        val number: String,
        val mark: Int,
        val note: String,
        val updatedAt: Long,
    )

    private const val TAG = "SpamStore"
    private const val DB_NAME = "spam_marks.db"
    private const val DB_VERSION = 1

    private class Helper(context: Context) : SQLiteOpenHelper(
        context.applicationContext, DB_NAME, null, DB_VERSION
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE marks (
                    number TEXT PRIMARY KEY,
                    mark INTEGER NOT NULL,
                    note TEXT NOT NULL DEFAULT '',
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_marks_mark ON marks(mark)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 首版无需迁移
        }
    }

    @Volatile
    private var helper: Helper? = null

    private fun db(context: Context): SQLiteDatabase? =
        runCatching {
            helper ?: Helper(context).also { helper = it }
        }.getOrNull()?.writableDatabase

    /** 返回 [MARK_WHITE_LABEL] / [MARK_BLACK_LABEL]，未标记返回 0 */
    fun getMark(context: Context, number: String): Int {
        val digits = PhoneLocationDb.normalize(number) ?: return 0
        val database = db(context) ?: return 0
        return runCatching {
            database.rawQuery("SELECT mark FROM marks WHERE number = ?", arrayOf(digits)).use { c ->
                if (c.moveToFirst()) c.getInt(0) else 0
            }
        }.onFailure { Log.w(TAG, "getMark failed", it) }.getOrDefault(0)
    }

    fun setMark(context: Context, number: String, mark: Int, note: String = "") {
        val digits = PhoneLocationDb.normalize(number) ?: return
        val database = db(context) ?: return
        runCatching {
            if (mark == 0) {
                database.delete("marks", "number = ?", arrayOf(digits))
            } else {
                val values = ContentValues().apply {
                    put("number", digits)
                    put("mark", mark)
                    put("note", note)
                    put("updated_at", System.currentTimeMillis())
                }
                database.insertWithOnConflict(
                    "marks", null, values, SQLiteDatabase.CONFLICT_REPLACE
                )
            }
        }.onFailure { Log.w(TAG, "setMark failed", it) }
    }

    fun list(context: Context, mark: Int? = null): List<Marked> {
        val database = db(context) ?: return emptyList()
        return runCatching {
            val sql: String
            val args: Array<String>?
            if (mark == null) {
                sql = "SELECT number, mark, note, updated_at FROM marks ORDER BY updated_at DESC"
                args = null
            } else {
                sql = "SELECT number, mark, note, updated_at FROM marks " +
                        "WHERE mark = ? ORDER BY updated_at DESC"
                args = arrayOf(mark.toString())
            }
            database.rawQuery(sql, args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(Marked(c.getString(0), c.getInt(1), c.getString(2), c.getLong(3)))
                    }
                }
            }
        }.onFailure { Log.w(TAG, "list failed", it) }.getOrDefault(emptyList())
    }
}
