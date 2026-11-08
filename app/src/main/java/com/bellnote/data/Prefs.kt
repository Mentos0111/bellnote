package com.bellnote.data

import android.content.Context
import android.content.SharedPreferences

/** 全部可配置项集中在这里，避免设置页/服务各写一套 key。 */
object Prefs {

    private const val FILE = "bellnote_prefs"

    private const val KEY_CALENDAR_ID = "calendar_id"
    private const val KEY_REMIND_LIST = "remind_list"
    private const val KEY_REMIND1 = "remind_minutes_1"
    private const val KEY_REMIND2 = "remind_minutes_2"

    const val MAX_REMINDERS = 3
    private const val KEY_API_KEY = "deepseek_api_key"
    private const val KEY_MODEL = "deepseek_vlm_model"
    private const val KEY_AUTO_IMPORT = "auto_import"
    private const val KEY_STRICT_PARSE = "strict_parse"
    private const val KEY_LAST_SIG = "last_notif_sig"
    private const val KEY_LAST_AT = "last_notif_at"

    private const val KEY_RECONCILE_ENABLED = "reconcile_enabled"
    private const val KEY_RECONCILE_INTERVAL = "reconcile_interval"
    private const val KEY_RECONCILE_NOTIFY = "reconcile_notify"
    private const val KEY_RECONCILE_LAST_AT = "reconcile_last_at"
    private const val KEY_RECONCILE_LAST_SUMMARY = "reconcile_last_summary"
    private const val KEY_BASELINE_DONE = "reconcile_baseline_done"

    const val DEFAULT_RECONCILE_INTERVAL = 30
    const val MIN_RECONCILE_INTERVAL = 15

    const val DEFAULT_MODEL = "deepseek-flash"

    /** 早期版本写死的模型名，平台并不支持，读到时当成没配过。 */
    private const val LEGACY_DEFAULT_MODEL = "deepseek-v4-flash-vision"

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun calendarId(context: Context): Long = sp(context).getLong(KEY_CALENDAR_ID, -1L)

    fun setCalendarId(context: Context, id: Long) {
        sp(context).edit().putLong(KEY_CALENDAR_ID, id).apply()
    }

    /** 提醒列表（分钟），最多 3 条；0 或负数会被丢掉。 */
    fun remindMinutes(context: Context): List<Int> {
        val p = sp(context)
        val raw = p.getString(KEY_REMIND_LIST, null)
        if (!raw.isNullOrBlank()) {
            val parsed = raw.split(',', '，', ' ')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 0 }
                .distinct()
                .take(MAX_REMINDERS)
            if (parsed.isNotEmpty()) return parsed
        }
        val first = p.getInt(KEY_REMIND1, 1440)
        val second = p.getInt(KEY_REMIND2, 60)
        return listOf(first, second).filter { it > 0 }.distinct().take(MAX_REMINDERS)
    }

    fun setRemindMinutes(context: Context, minutes: List<Int>) {
        val cleaned = minutes.filter { it > 0 }.distinct().take(MAX_REMINDERS)
        sp(context).edit().putString(KEY_REMIND_LIST, cleaned.joinToString(",")).apply()
    }

    fun apiKey(context: Context): String = sp(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, key: String) {
        sp(context).edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun model(context: Context): String {
        val stored = sp(context).getString(KEY_MODEL, DEFAULT_MODEL)?.trim().orEmpty()
        if (stored.isEmpty() || stored == LEGACY_DEFAULT_MODEL) return DEFAULT_MODEL
        return stored
    }

    fun setModel(context: Context, model: String) {
        sp(context).edit().putString(KEY_MODEL, model.trim().ifBlank { DEFAULT_MODEL }).apply()
    }

    fun autoImport(context: Context): Boolean = sp(context).getBoolean(KEY_AUTO_IMPORT, true)

    fun setAutoImport(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_AUTO_IMPORT, on).apply()
    }

    /**
     * 严格模式（默认开）：只有时间真从消息里解析出来的才写进日历。
     * 关掉后，认不出格式的通知也会按「当前时间 +1 小时」塞进日历 —— 容易堆垃圾，只在明确需要时关。
     */
    fun strictParse(context: Context): Boolean = sp(context).getBoolean(KEY_STRICT_PARSE, true)

    fun setStrictParse(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_STRICT_PARSE, on).apply()
    }

    // ---------------- 定时对账 ----------------

    /** 同一条通知可能在短时间内重复到达，60 秒内同内容只处理一次。 */
    fun shouldHandle(context: Context, signature: String, now: Long = System.currentTimeMillis()): Boolean {
        val p = sp(context)
        val last = p.getString(KEY_LAST_SIG, "").orEmpty()
        val lastAt = p.getLong(KEY_LAST_AT, 0L)
        if (last == signature && now - lastAt < 60_000L) return false
        p.edit().putString(KEY_LAST_SIG, signature).putLong(KEY_LAST_AT, now).apply()
        return true
    }

    // ---------------- 定时对账 ----------------

    fun reconcileEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_RECONCILE_ENABLED, true)

    fun setReconcileEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_RECONCILE_ENABLED, on).apply()
    }

    /** 对账间隔（分钟），最小 15，默认 30。 */
    fun reconcileInterval(context: Context): Int =
        sp(context).getInt(KEY_RECONCILE_INTERVAL, DEFAULT_RECONCILE_INTERVAL)
            .coerceAtLeast(MIN_RECONCILE_INTERVAL)

    fun setReconcileInterval(context: Context, minutes: Int) {
        sp(context).edit()
            .putInt(KEY_RECONCILE_INTERVAL, minutes.coerceAtLeast(MIN_RECONCILE_INTERVAL))
            .apply()
    }

    fun reconcileNotify(context: Context): Boolean =
        sp(context).getBoolean(KEY_RECONCILE_NOTIFY, true)

    fun setReconcileNotify(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_RECONCILE_NOTIFY, on).apply()
    }

    fun lastReconcileAt(context: Context): Long = sp(context).getLong(KEY_RECONCILE_LAST_AT, 0L)

    fun setLastReconcileAt(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_RECONCILE_LAST_AT, at).apply()
    }

    fun lastReconcileSummary(context: Context): String =
        sp(context).getString(KEY_RECONCILE_LAST_SUMMARY, "").orEmpty()

    fun setLastReconcileSummary(context: Context, summary: String) {
        sp(context).edit().putString(KEY_RECONCILE_LAST_SUMMARY, summary).apply()
    }

    /** 2.2 首次对账要把日历里已有的待办收进本地账当基线，只做一次。 */
    fun baselineDone(context: Context): Boolean = sp(context).getBoolean(KEY_BASELINE_DONE, false)

    fun setBaselineDone(context: Context, done: Boolean) {
        sp(context).edit().putBoolean(KEY_BASELINE_DONE, done).apply()
    }

    fun hasCalendarPermission(context: Context): Boolean =
        android.content.pm.PackageManager.PERMISSION_GRANTED ==
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.WRITE_CALENDAR
            )
}
