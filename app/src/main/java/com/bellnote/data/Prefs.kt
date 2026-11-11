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

    /** 截图识别现场：进程被杀 / 用户切走再回来，能拿回上一次的识别结果，不用重选图重花钱。 */
    private const val KEY_IMPORT_URI = "import_pending_uri"
    private const val KEY_IMPORT_RAW = "import_pending_raw"
    private const val KEY_IMPORT_AT = "import_pending_at"

    /** 截图监听：截完图自动问「要不要识别成待办」。 */
    private const val KEY_SHOT_WATCH = "shot_watch_enabled"
    private const val KEY_LAST_SHOT_ID = "last_shot_id"

    private const val KEY_RECONCILE_ENABLED = "reconcile_enabled"
    private const val KEY_RECONCILE_INTERVAL = "reconcile_interval"
    private const val KEY_RECONCILE_NOTIFY = "reconcile_notify"
    private const val KEY_RECONCILE_LAST_AT = "reconcile_last_at"
    private const val KEY_RECONCILE_LAST_SUMMARY = "reconcile_last_summary"
    private const val KEY_BASELINE_DONE = "reconcile_baseline_done"

    private const val KEY_IGNORED = "ignored_notices"
    private const val KEY_NTFY_TOPIC = "ntfy_topic"
    private const val KEY_NTFY_SERVER = "ntfy_server"
    private const val KEY_NTFY_SINCE = "ntfy_since"
    private const val KEY_CATCHUP_AT = "catchup_last_at"
    private const val KEY_CATCHUP_SUMMARY = "catchup_last_summary"
    private const val KEY_LISTENER_AT = "listener_connected_at"
    private const val KEY_THEME = "theme_palette"
    private const val KEY_HEALTH = "health_cache"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_HEALTH_AT = "health_cached_at"
    private const val KEY_COLLAPSED_GROUPS = "list_collapsed_groups"

    /** 存「被忽略的通知」用的分隔符：\u0001 分条、\u0002 分字段，正常正文里不会出现。 */
    private const val ENTRY_SEP = "\u0001"
    private const val FIELD_SEP = "\u0002"

    /** 默认收起的分组：过期的事平时不用看，但收起时标题上会写条数。 */
    private const val DEFAULT_COLLAPSED_GROUP = "已过期"

    const val DEFAULT_NTFY_SERVER = "https://ntfy.sh"
    const val MAX_IGNORED = 10


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

    // ---------------- 截图监听 ----------------

    fun shotWatchEnabled(context: Context): Boolean = sp(context).getBoolean(KEY_SHOT_WATCH, true)

    fun setShotWatchEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_SHOT_WATCH, on).apply()
    }

    /** 已经提醒过的最后一张截图的 MediaStore id，避免同一张反复弹。 */
    fun lastShotId(context: Context): Long = sp(context).getLong(KEY_LAST_SHOT_ID, -1L)

    fun setLastShotId(context: Context, id: Long) {
        sp(context).edit().putLong(KEY_LAST_SHOT_ID, id).apply()
    }

    // ---------------- 截图识别现场 ----------------

    data class PendingImport(val uri: String, val raw: String, val at: Long)

    fun pendingImport(context: Context): PendingImport? {
        val p = sp(context)
        val raw = p.getString(KEY_IMPORT_RAW, "").orEmpty()
        if (raw.isBlank()) return null
        return PendingImport(
            uri = p.getString(KEY_IMPORT_URI, "").orEmpty(),
            raw = raw,
            at = p.getLong(KEY_IMPORT_AT, 0L)
        )
    }

    fun savePendingImport(context: Context, uri: String, raw: String) {
        if (raw.isBlank()) return
        sp(context).edit()
            .putString(KEY_IMPORT_URI, uri)
            .putString(KEY_IMPORT_RAW, raw)
            .putLong(KEY_IMPORT_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearPendingImport(context: Context) {
        sp(context).edit()
            .remove(KEY_IMPORT_URI)
            .remove(KEY_IMPORT_RAW)
            .remove(KEY_IMPORT_AT)
            .apply()
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

    // ---------------- ntfy 补查 ----------------

    /**
     * ntfy 主题。填了才开启补查：App 起来 / 定时对账时直接拉服务器缓存，
     * 不再只靠「ntfy App 会不会弹通知」这一条命脉。留空 = 保持旧行为。
     */
    fun ntfyTopic(context: Context): String = sp(context).getString(KEY_NTFY_TOPIC, "").orEmpty().trim()

    fun setNtfyTopic(context: Context, topic: String) {
        sp(context).edit().putString(KEY_NTFY_TOPIC, topic.trim()).apply()
    }

    fun ntfyServer(context: Context): String =
        sp(context).getString(KEY_NTFY_SERVER, DEFAULT_NTFY_SERVER).orEmpty()
            .trim().ifBlank { DEFAULT_NTFY_SERVER }.trimEnd('/')

    fun setNtfyServer(context: Context, server: String) {
        sp(context).edit().putString(KEY_NTFY_SERVER, server.trim()).apply()
    }

    /** 上次补查到的消息时间（epoch 秒），作为下次的 since。 */
    fun ntfySince(context: Context): Long = sp(context).getLong(KEY_NTFY_SINCE, 0L)

    fun setNtfySince(context: Context, seconds: Long) {
        sp(context).edit().putLong(KEY_NTFY_SINCE, seconds).apply()
    }

    fun lastCatchUpAt(context: Context): Long = sp(context).getLong(KEY_CATCHUP_AT, 0L)

    fun setLastCatchUpAt(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_CATCHUP_AT, at).apply()
    }

    fun lastCatchUpSummary(context: Context): String =
        sp(context).getString(KEY_CATCHUP_SUMMARY, "").orEmpty()

    fun setLastCatchUpSummary(context: Context, summary: String) {
        sp(context).edit().putString(KEY_CATCHUP_SUMMARY, summary).apply()
    }

    // ---------------- 被忽略的推送（排查用） ----------------

    data class IgnoredNotice(val at: Long, val reason: String, val text: String)

    /**
     * 最近被丢弃 / 写失败的通知。以前这类事只写 logcat，手机上一觉醒来什么都查不到；
     * 现在留最近 [MAX_IGNORED] 条，设置页能直接看。
     */
    fun ignoredNotices(context: Context): List<IgnoredNotice> {
        val raw = sp(context).getString(KEY_IGNORED, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split(ENTRY_SEP).mapNotNull { entry ->
            val parts = entry.split(FIELD_SEP)
            if (parts.size < 3) return@mapNotNull null
            IgnoredNotice(
                at = parts[0].toLongOrNull() ?: 0L,
                reason = parts[1],
                text = parts[2]
            )
        }
    }

    fun addIgnoredNotice(context: Context, reason: String, text: String) {
        val oneLine = text
            .replace('\n', ' ')
            .replace(ENTRY_SEP, " ")
            .replace(FIELD_SEP, " ")
            .take(200)
        val entry = listOf(System.currentTimeMillis().toString(), reason, oneLine).joinToString(FIELD_SEP)
        val previous = sp(context).getString(KEY_IGNORED, "").orEmpty()
            .split(ENTRY_SEP)
            .filter { it.isNotBlank() }
        val kept = (listOf(entry) + previous).take(MAX_IGNORED)
        sp(context).edit().putString(KEY_IGNORED, kept.joinToString(ENTRY_SEP)).apply()
    }

    fun clearIgnoredNotices(context: Context) {
        sp(context).edit().remove(KEY_IGNORED).apply()
    }

    // ---------------- 首次引导 ----------------

    fun onboardingDone(context: Context): Boolean = sp(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboardingDone(context: Context, done: Boolean) {
        sp(context).edit().putBoolean(KEY_ONBOARDED, done).apply()
    }

    // ---------------- 体检结果缓存 ----------------

    fun healthCache(context: Context): String = sp(context).getString(KEY_HEALTH, "").orEmpty()

    fun healthCachedAt(context: Context): Long = sp(context).getLong(KEY_HEALTH_AT, 0L)

    fun setHealthCache(context: Context, raw: String, at: Long) {
        sp(context).edit().putString(KEY_HEALTH, raw).putLong(KEY_HEALTH_AT, at).apply()
    }

    // ---------------- 外观主题 ----------------

    /** 配色主题 id：teal / indigo / sand / ink（默认青绿）。 */
    fun theme(context: Context): String =
        sp(context).getString(KEY_THEME, "teal").orEmpty().ifBlank { "teal" }

    fun setTheme(context: Context, id: String) {
        sp(context).edit().putString(KEY_THEME, id).apply()
    }

    // ---------------- 监听健康 ----------------

    /** 通知监听最近一次连上的时间：用来判断「授权还在但服务被系统回收了」。 */
    fun listenerConnectedAt(context: Context): Long = sp(context).getLong(KEY_LISTENER_AT, 0L)

    fun setListenerConnectedAt(context: Context, at: Long) {
        sp(context).edit().putLong(KEY_LISTENER_AT, at).apply()
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

    /** 列表里哪些分组是收起来的（默认只收「已过期」）。 */
    fun collapsedGroups(context: Context): Set<String> {
        val raw = sp(context).getString(KEY_COLLAPSED_GROUPS, null)
            ?: return setOf(DEFAULT_COLLAPSED_GROUP)
        return raw.split('\u0001').filter { it.isNotBlank() }.toSet()
    }

    fun setCollapsedGroups(context: Context, labels: Set<String>) {
        sp(context).edit().putString(KEY_COLLAPSED_GROUPS, labels.joinToString("\u0001")).apply()
    }
}
