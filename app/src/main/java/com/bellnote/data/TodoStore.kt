package com.bellnote.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 本地账本里的一条待办：ntfy 发过来、或手动/截图加进日历的东西。
 * 这份账就是「ntfy 发的清单」的真身，定时对账拿它和日历比。
 */
data class LocalTodo(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val durationMinutes: Int,
    val note: String?,
    val repeat: RepeatRule,
    val eventId: Long,
    val source: String
) {
    fun toSpec(): TodoSpec = TodoSpec(
        title = title,
        startMillis = startMillis,
        durationMinutes = durationMinutes,
        note = note,
        repeat = repeat
    )
}

private const val DB_NAME = "bellnote_todos.db"
private const val DB_VERSION = 1
private const val TABLE = "todos"

private const val COL_ID = "id"
private const val COL_TITLE = "title"
private const val COL_START = "start_millis"
private const val COL_DURATION = "duration_minutes"
private const val COL_NOTE = "note"
private const val COL_REPEAT = "repeat_rule"
private const val COL_EVENT_ID = "event_id"
private const val COL_STATUS = "status"
private const val COL_SOURCE = "source"
private const val COL_CREATED = "created_at"
private const val COL_UPDATED = "updated_at"

const val STATUS_ACTIVE = "active"
const val STATUS_DELETED = "deleted"

/** 来源，只用于展示和排查。 */
const val SOURCE_NTFY = "ntfy"
const val SOURCE_MANUAL = "manual"
const val SOURCE_SHOT = "shot"
const val SOURCE_BASELINE = "baseline"

/** 本地账本：ntfy 发过来的清单落在这里，写日历成功与否都记一笔。 */
class TodoStore(context: Context) {

    // 全 App 共用一个 SQLiteOpenHelper：每次 new 一个会各开一个连接池，跑几次对账就泄漏一堆
    private val helper: Helper = sharedHelper(context.applicationContext)

    @Synchronized
    fun count(): Int = try {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    } catch (e: Exception) {
        Log.w(TAG, "统计本地账本失败", e)
        0
    }

    /** 当前「应该有」的全部条目，按时间升序。 */
    @Synchronized
    fun all(): List<LocalTodo> = try {
        helper.readableDatabase.rawQuery(
            "SELECT * FROM $TABLE WHERE $COL_STATUS = ? ORDER BY $COL_START ASC",
            arrayOf(STATUS_ACTIVE)
        ).use { cursor -> collect(cursor) }
    } catch (e: Exception) {
        Log.w(TAG, "读取本地账本失败", e)
        emptyList()
    }

    /** 写日历成功后记一笔；同标题同时间视为同一条，重复推送不会变成两条。 */
    @Synchronized
    fun record(spec: TodoSpec, eventId: Long, source: String) {
        try {
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put(COL_TITLE, spec.title)
                put(COL_START, spec.startMillis)
                put(COL_DURATION, spec.durationMinutes)
                put(COL_NOTE, spec.note)
                put(COL_REPEAT, spec.repeat.name)
                put(COL_STATUS, STATUS_ACTIVE)
                put(COL_SOURCE, source)
                put(COL_UPDATED, now)
            }
            if (eventId > 0) values.put(COL_EVENT_ID, eventId)
            val existingId = findId(db, spec.title, spec.startMillis)
            if (existingId > 0) {
                db.update(TABLE, values, "$COL_ID = ?", arrayOf(existingId.toString()))
            } else {
                values.put(COL_CREATED, now)
                if (eventId <= 0) values.put(COL_EVENT_ID, -1L)
                db.insert(TABLE, null, values)
            }
        } catch (e: Exception) {
            Log.w(TAG, "写本地账本失败", e)
        }
    }

    /** 编辑页改了某条事件，按 eventId 同步本地账。 */
    @Synchronized
    fun updateByEventId(eventId: Long, spec: TodoSpec) {
        if (eventId <= 0) return
        try {
            val values = ContentValues().apply {
                put(COL_TITLE, spec.title)
                put(COL_START, spec.startMillis)
                put(COL_DURATION, spec.durationMinutes)
                put(COL_NOTE, spec.note)
                put(COL_REPEAT, spec.repeat.name)
                put(COL_UPDATED, System.currentTimeMillis())
            }
            helper.writableDatabase.update(TABLE, values, "$COL_EVENT_ID = ?", arrayOf(eventId.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "按 eventId 更新本地账失败", e)
        }
    }

    /** 补齐缺失后把新 eventId 回写到本地账。 */
    @Synchronized
    fun attachEventId(localId: Long, eventId: Long) {
        if (localId <= 0 || eventId <= 0) return
        try {
            val values = ContentValues().apply {
                put(COL_EVENT_ID, eventId)
                put(COL_UPDATED, System.currentTimeMillis())
            }
            helper.writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(localId.toString()))
        } catch (e: Exception) {
            Log.w(TAG, "回写 eventId 失败", e)
        }
    }

    /** 物理删掉很久以前标记为删除的记录，避免账本无限变长。 */
    @Synchronized
    fun pruneDeleted(olderThanMillis: Long): Int = try {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        helper.writableDatabase.delete(
            TABLE,
            "$COL_STATUS = ? AND $COL_UPDATED < ?",
            arrayOf(STATUS_DELETED, cutoff.toString())
        )
    } catch (e: Exception) {
        Log.w(TAG, "清理旧账失败", e)
        0
    }

    @Synchronized
    fun markDeletedByIds(localIds: Collection<Long>) {
        val ids = localIds.filter { it > 0 }
        if (ids.isEmpty()) return
        try {
            val args = ids.map { it.toString() }.toTypedArray()
            val placeholders = args.joinToString(",") { "?" }
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_DELETED)
                put(COL_UPDATED, System.currentTimeMillis())
            }
            helper.writableDatabase.update(TABLE, values, "$COL_ID IN ($placeholders)", args)
        } catch (e: Exception) {
            Log.w(TAG, "按 id 标记删除失败", e)
        }
    }

    @Synchronized
    fun markDeletedByEventIds(eventIds: Collection<Long>) {
        val ids = eventIds.filter { it > 0 }
        if (ids.isEmpty()) return
        try {
            val args = ids.map { it.toString() }.toTypedArray()
            val placeholders = args.joinToString(",") { "?" }
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_DELETED)
                put(COL_UPDATED, System.currentTimeMillis())
            }
            helper.writableDatabase.update(
                TABLE, values, "$COL_EVENT_ID IN ($placeholders) AND $COL_STATUS = ?",
                args + STATUS_ACTIVE
            )
        } catch (e: Exception) {
            Log.w(TAG, "按 eventId 标记删除失败", e)
        }
    }

    /**
     * 收到删除指令时同步本地账：标题必须完全一致，给了时间就只删那前后 30 分钟内的那条
     * （和 CalendarRepository.findOwnEvents 用同一个窗口，不然日历删了、账还在，对账会报「多」）。
     * 这样「日历里已经被手动删掉、本地账还留着」的条目也会一起清掉。
     */
    @Synchronized
    fun markDeletedByTitle(title: String, dayMillis: Long?) {
        val wanted = title.trim()
        if (wanted.isEmpty()) return
        try {
            val values = ContentValues().apply {
                put(COL_STATUS, STATUS_DELETED)
                put(COL_UPDATED, System.currentTimeMillis())
            }
            val db = helper.writableDatabase
            if (dayMillis == null) {
                db.update(
                    TABLE, values,
                    "$COL_TITLE = ? AND $COL_STATUS = ?",
                    arrayOf(wanted, STATUS_ACTIVE)
                )
            } else {
                val range = DeleteWindow.rangeOf(dayMillis)
                db.update(
                    TABLE, values,
                    "$COL_TITLE = ? AND $COL_START >= ? AND $COL_START <= ? AND $COL_STATUS = ?",
                    arrayOf(wanted, range.first.toString(), range.last.toString(), STATUS_ACTIVE)
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "按标题标记删除失败", e)
        }
    }

    // ---------------- 内部实现 ----------------

    private fun findId(db: SQLiteDatabase, title: String, startMillis: Long): Long = try {
        db.rawQuery(
            "SELECT $COL_ID FROM $TABLE WHERE $COL_TITLE = ? AND $COL_START = ? AND $COL_STATUS = ? " +
                "ORDER BY $COL_ID DESC LIMIT 1",
            arrayOf(title.trim(), startMillis.toString(), STATUS_ACTIVE)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
    } catch (e: Exception) {
        Log.w(TAG, "查重失败", e)
        -1L
    }

    private fun collect(cursor: Cursor): List<LocalTodo> {
        val out = mutableListOf<LocalTodo>()
        val idIdx = cursor.getColumnIndex(COL_ID)
        val titleIdx = cursor.getColumnIndex(COL_TITLE)
        val startIdx = cursor.getColumnIndex(COL_START)
        val durationIdx = cursor.getColumnIndex(COL_DURATION)
        val noteIdx = cursor.getColumnIndex(COL_NOTE)
        val repeatIdx = cursor.getColumnIndex(COL_REPEAT)
        val eventIdx = cursor.getColumnIndex(COL_EVENT_ID)
        val sourceIdx = cursor.getColumnIndex(COL_SOURCE)
        while (cursor.moveToNext()) {
            if (idIdx < 0 || startIdx < 0) continue
            out.add(
                LocalTodo(
                    id = cursor.getLong(idIdx),
                    title = if (titleIdx >= 0) cursor.getString(titleIdx).orEmpty() else "",
                    startMillis = cursor.getLong(startIdx),
                    durationMinutes = if (durationIdx >= 0) cursor.getInt(durationIdx) else DEFAULT_DURATION_MINUTES,
                    note = if (noteIdx >= 0) cursor.getString(noteIdx) else null,
                    repeat = RepeatRule.fromRaw(if (repeatIdx >= 0) cursor.getString(repeatIdx) else null),
                    eventId = if (eventIdx >= 0) cursor.getLong(eventIdx) else -1L,
                    source = if (sourceIdx >= 0) cursor.getString(sourceIdx).orEmpty() else SOURCE_NTFY
                )
            )
        }
        return out
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_TITLE TEXT NOT NULL,
                    $COL_START INTEGER NOT NULL,
                    $COL_DURATION INTEGER NOT NULL DEFAULT 5,
                    $COL_NOTE TEXT,
                    $COL_REPEAT TEXT NOT NULL DEFAULT 'NONE',
                    $COL_EVENT_ID INTEGER NOT NULL DEFAULT -1,
                    $COL_STATUS TEXT NOT NULL DEFAULT '$STATUS_ACTIVE',
                    $COL_SOURCE TEXT NOT NULL DEFAULT '$SOURCE_NTFY',
                    $COL_CREATED INTEGER NOT NULL DEFAULT 0,
                    $COL_UPDATED INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_todos_start ON $TABLE($COL_START)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 目前只有 v1，后续加字段在这里 ALTER TABLE，不要 DROP 用户数据。
        }
    }

    companion object {
        private const val TAG = "TodoStore"

        @Volatile
        private var shared: Helper? = null

        private fun sharedHelper(context: Context): Helper =
            shared ?: synchronized(this) {
                shared ?: Helper(context).also { shared = it }
            }
    }
}
