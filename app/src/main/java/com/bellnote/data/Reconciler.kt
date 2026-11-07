package com.bellnote.data

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 差异类型：日历缺了、日历多了、两边不一致。 */
enum class DiffKind(val badge: String) {
    MISSING("缺"),
    EXTRA("多"),
    CHANGED("改")
}

data class DiffItem(
    val kind: DiffKind,
    val title: String,
    val startMillis: Long,
    val local: LocalTodo?,
    val calendar: TodoItem?,
    val detail: String
)

data class ReconcileReport(
    val localCount: Int,
    val calendarCount: Int,
    val diffs: List<DiffItem>,
    val baselineImported: Int = 0,
    val checkedAt: Long = System.currentTimeMillis()
) {
    val missing: List<DiffItem> get() = diffs.filter { it.kind == DiffKind.MISSING }
    val extra: List<DiffItem> get() = diffs.filter { it.kind == DiffKind.EXTRA }
    val changed: List<DiffItem> get() = diffs.filter { it.kind == DiffKind.CHANGED }
    val consistent: Boolean get() = diffs.isEmpty()

    fun summary(): String {
        if (consistent) return "一致"
        val parts = mutableListOf<String>()
        if (missing.isNotEmpty()) parts.add("缺 ${missing.size}")
        if (extra.isNotEmpty()) parts.add("多 ${extra.size}")
        if (changed.isNotEmpty()) parts.add("不一致 ${changed.size}")
        return parts.joinToString(" · ")
    }
}

/**
 * 对账：本地账（ntfy 说应该有）vs 日历（实际落地）。
 * [compare] 是纯函数，不碰 Android，便于跑单测。
 */
object Reconciler {

    private const val TAG = "Reconciler"

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** 纯比对：返回三类差异，按时间升序。 */
    fun compare(local: List<LocalTodo>, calendar: List<TodoItem>): List<DiffItem> {
        val diffs = mutableListOf<DiffItem>()
        val calById = calendar.associateBy { it.id }
        val usedCal = HashSet<Long>()

        // 第一轮：靠 eventId 精确配对（最可靠）
        val pending = mutableListOf<LocalTodo>()
        for (item in local) {
            val cal = if (item.eventId > 0) calById[item.eventId] else null
            if (cal != null) {
                usedCal.add(cal.id)
                val why = describeChange(item, cal)
                if (why != null) {
                    diffs.add(DiffItem(DiffKind.CHANGED, item.title, item.startMillis, item, cal, why))
                }
            } else {
                pending.add(item)
            }
        }

        val calLeft = calendar.filter { it.id !in usedCal }
        val usedLeft = HashSet<Long>()
        val unmatched = mutableListOf<LocalTodo>()

        // 第二轮：标题 + 开始时间一致
        for (item in pending) {
            val cal = calLeft.firstOrNull {
                it.id !in usedLeft && sameTitle(it.title, item.title) && it.startMillis == item.startMillis
            }
            if (cal != null) {
                usedLeft.add(cal.id)
                val why = describeChange(item, cal)
                if (why != null) {
                    diffs.add(DiffItem(DiffKind.CHANGED, item.title, item.startMillis, item, cal, why))
                }
            } else {
                unmatched.add(item)
            }
        }

        // 第三轮：标题对得上但时间变了 —— 归为「不一致」而不是「缺 + 多」
        val stillMissing = mutableListOf<LocalTodo>()
        for (item in unmatched) {
            val cal = calLeft.firstOrNull { it.id !in usedLeft && sameTitle(it.title, item.title) }
            if (cal != null) {
                usedLeft.add(cal.id)
                diffs.add(
                    DiffItem(
                        DiffKind.CHANGED, item.title, item.startMillis, item, cal,
                        "日历里是 ${format(cal.startMillis)}，清单是 ${format(item.startMillis)}"
                    )
                )
            } else {
                stillMissing.add(item)
            }
        }

        for (item in stillMissing) {
            diffs.add(
                DiffItem(DiffKind.MISSING, item.title, item.startMillis, item, null, "日历里没有这条")
            )
        }
        for (cal in calLeft) {
            if (cal.id in usedLeft) continue
            diffs.add(
                DiffItem(DiffKind.EXTRA, cal.title, cal.startMillis, null, cal, "本地清单里没有这条")
            )
        }
        return diffs.sortedBy { it.startMillis }
    }

    /** 跑一次完整对账（含首次基线导入）。 */
    fun run(context: Context): ReconcileReport {
        val store = TodoStore(context)
        val repository = CalendarRepository(context)
        val calendar = repository.queryOwnEvents()
        val imported = importBaselineIfNeeded(context, store, calendar)
        val local = store.all()
        val diffs = compare(local, calendar)
        Log.i(TAG, "对账：清单 ${local.size} 条，日历 ${calendar.size} 条，差异 ${diffs.size} 条")
        return ReconcileReport(local.size, calendar.size, diffs, imported)
    }

    /** 补齐缺失：把清单里有、日历里没有的写回日历。 */
    fun applyMissing(context: Context, diffs: List<DiffItem>): Int {
        val store = TodoStore(context)
        val repository = CalendarRepository(context)
        val calendarId = repository.defaultCalendarId()
        if (calendarId <= 0) return 0
        var done = 0
        for (diff in diffs) {
            val item = diff.local ?: continue
            val eventId = repository.insertEvent(item.toSpec(), calendarId)
            if (eventId > 0) {
                store.attachEventId(item.id, eventId)
                done++
            }
        }
        Log.i(TAG, "补齐缺失 $done 条")
        return done
    }

    /** 清理多余：删掉日历里有、清单里没有的本 App 事件。 */
    fun applyExtra(context: Context, diffs: List<DiffItem>): Int {
        val store = TodoStore(context)
        val repository = CalendarRepository(context)
        var done = 0
        val removed = mutableListOf<Long>()
        for (diff in diffs) {
            val cal = diff.calendar ?: continue
            if (repository.deleteEvent(cal.id)) {
                removed.add(cal.id)
                done++
            }
        }
        if (removed.isNotEmpty()) store.markDeletedByEventIds(removed)
        Log.i(TAG, "清理多余 $done 条")
        return done
    }

    /** 修正不一致：以本地清单为准，把日历改回来。 */
    fun applyChanged(context: Context, diffs: List<DiffItem>): Int {
        val store = TodoStore(context)
        val repository = CalendarRepository(context)
        var done = 0
        for (diff in diffs) {
            val item = diff.local ?: continue
            val cal = diff.calendar ?: continue
            val calendarId = if (cal.calendarId > 0) cal.calendarId else repository.defaultCalendarId()
            if (calendarId <= 0) continue
            if (repository.updateEvent(cal.id, item.toSpec(), calendarId)) {
                store.attachEventId(item.id, cal.id)
                done++
            }
        }
        Log.i(TAG, "修正不一致 $done 条")
        return done
    }

    // ---------------- 内部实现 ----------------

    /**
     * 升级到 2.2 首次运行时，日历里已经有一堆 v2.1 写的待办、本地账却是空的。
     * 直接把现有事件收进账里当基线，避免一打开就报几十条「多」。
     */
    private fun importBaselineIfNeeded(
        context: Context,
        store: TodoStore,
        calendar: List<TodoItem>
    ): Int {
        if (Prefs.baselineDone(context)) return 0
        var imported = 0
        for (item in calendar) {
            store.record(item.toSpec(), item.id, SOURCE_BASELINE)
            imported++
        }
        Prefs.setBaselineDone(context, true)
        if (imported > 0) Log.i(TAG, "首次对账：把现有 $imported 条日历待办导入为基线")
        return imported
    }

    private fun describeChange(local: LocalTodo, calendar: TodoItem): String? {
        val parts = mutableListOf<String>()
        if (local.startMillis != calendar.startMillis) {
            parts.add("时间 ${format(local.startMillis)} → ${format(calendar.startMillis)}")
        }
        if (local.durationMinutes != calendar.durationMinutes) {
            parts.add("时长 ${local.durationMinutes} → ${calendar.durationMinutes} 分钟")
        }
        if (local.repeat != calendar.repeat) {
            parts.add("重复 ${local.repeat.label} → ${calendar.repeat.label}")
        }
        if (!sameTitle(local.title, calendar.title)) {
            parts.add("标题「${calendar.title}」")
        }
        return if (parts.isEmpty()) null else parts.joinToString("，")
    }

    private fun sameTitle(first: String, second: String): Boolean {
        fun norm(raw: String) = raw.trim().replace(Regex("\\s+"), " ")
        return norm(first).equals(norm(second), ignoreCase = true)
    }

    private fun format(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
}
