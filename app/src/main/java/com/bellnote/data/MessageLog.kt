package com.bellnote.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 推送消息收件箱：每一条进出的消息都记一笔，以及它最后怎么处理的。
 *
 * 为什么单独一个库：待办账本（bellnote_todos.db）是对账用的真身，不能因为「看日志」这类需求
 * 去动它的表结构；日志可以整体清空、可以裁剪，坏了也不影响日历。所以另开一个库。
 */
object MessageLog {

    const val DIR_IN = "in"
    const val DIR_OUT = "out"

    const val RESULT_OK = "ok"
    const val RESULT_UPDATED = "updated"
    const val RESULT_DROPPED = "dropped"
    const val RESULT_FAILED = "failed"
    const val RESULT_SENT = "sent"
    const val RESULT_INFO = "info"

    /** 只留最近这么多条；日志是排查用的，不是档案。 */
    const val MAX = 200

    private const val TAG = "MessageLog"
    private const val DB_NAME = "bellnote_messages.db"
    private const val TABLE = "messages"

    private const val COL_ID = "id"
    private const val COL_AT = "at"
    private const val COL_DIRECTION = "direction"
    private const val COL_SOURCE = "source"
    private const val COL_TITLE = "title"
    private const val COL_BODY = "body"
    private const val COL_RESULT = "result"
    private const val COL_DETAIL = "detail"

    data class Entry(
        val id: Long,
        val at: Long,
        val direction: String,
        val source: String,
        val title: String,
        val body: String,
        val result: String,
        val detail: String
    ) {
        val fromUser: Boolean get() = direction == DIR_IN
        val canRetry: Boolean
            get() = fromUser && (result == RESULT_DROPPED || result == RESULT_FAILED)
    }

    /** 收件箱里的结果要翻成中文，别把英文常量摊到界面上。 */
    fun resultLabel(result: String): String = when (result) {
        RESULT_OK -> "已进日历"
        RESULT_UPDATED -> "已更新"
        RESULT_DROPPED -> "没写进日历"
        RESULT_FAILED -> "失败"
        RESULT_SENT -> "已发出"
        else -> "已处理"
    }

    fun log(
        context: Context,
        direction: String,
        source: String,
        title: String,
        body: String,
        result: String,
        detail: String
    ) {
        val db = try {
            helper(context).writableDatabase
        } catch (e: Exception) {
            Log.w(TAG, "打开消息库失败", e)
            return
        }
        try {
            val values = ContentValues().apply {
                put(COL_AT, System.currentTimeMillis())
                put(COL_DIRECTION, direction)
                put(COL_SOURCE, source.take(40))
                put(COL_TITLE, title.trim().take(200))
                put(COL_BODY, body.take(4000))
                put(COL_RESULT, result)
                put(COL_DETAIL, detail.take(1000))
            }
            db.insert(TABLE, null, values)
            // 超出上限的老记录直接删，别让日志库无限长
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_ID NOT IN " +
                    "(SELECT $COL_ID FROM $TABLE ORDER BY $COL_ID DESC LIMIT $MAX)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "写消息记录失败", e)
        }
    }

    fun recent(context: Context, limit: Int = MAX): List<Entry> = try {
        helper(context).readableDatabase.rawQuery(
            "SELECT * FROM $TABLE ORDER BY $COL_ID DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor -> collect(cursor) }
    } catch (e: Exception) {
        Log.w(TAG, "读消息记录失败", e)
        emptyList()
    }

    fun count(context: Context): Int = try {
        helper(context).readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "统计消息记录失败", e)
        0
    }

    fun clear(context: Context) {
        try {
            helper(context).writableDatabase.delete(TABLE, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "清空消息记录失败", e)
        }
    }

    private fun collect(cursor: Cursor): List<Entry> {
        val out = mutableListOf<Entry>()
        val idIdx = cursor.getColumnIndex(COL_ID)
        val atIdx = cursor.getColumnIndex(COL_AT)
        val dirIdx = cursor.getColumnIndex(COL_DIRECTION)
        val srcIdx = cursor.getColumnIndex(COL_SOURCE)
        val titleIdx = cursor.getColumnIndex(COL_TITLE)
        val bodyIdx = cursor.getColumnIndex(COL_BODY)
        val resultIdx = cursor.getColumnIndex(COL_RESULT)
        val detailIdx = cursor.getColumnIndex(COL_DETAIL)
        while (cursor.moveToNext()) {
            out.add(
                Entry(
                    id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L,
                    at = if (atIdx >= 0) cursor.getLong(atIdx) else 0L,
                    direction = if (dirIdx >= 0) cursor.getString(dirIdx).orEmpty() else DIR_IN,
                    source = if (srcIdx >= 0) cursor.getString(srcIdx).orEmpty() else "",
                    title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else "",
                    body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else "",
                    result = if (resultIdx >= 0) cursor.getString(resultIdx).orEmpty() else RESULT_INFO,
                    detail = if (detailIdx >= 0) cursor.getString(detailIdx).orEmpty() else ""
                )
            )
        }
        return out
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_AT INTEGER NOT NULL,
                    $COL_DIRECTION TEXT NOT NULL,
                    $COL_SOURCE TEXT NOT NULL DEFAULT '',
                    $COL_TITLE TEXT NOT NULL DEFAULT '',
                    $COL_BODY TEXT NOT NULL DEFAULT '',
                    $COL_RESULT TEXT NOT NULL DEFAULT '$RESULT_INFO',
                    $COL_DETAIL TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_messages_at ON $TABLE($COL_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Volatile
    private var shared: Helper? = null

    private fun helper(context: Context): Helper =
        shared ?: synchronized(this) {
            shared ?: Helper(context.applicationContext).also { shared = it }
        }
}
