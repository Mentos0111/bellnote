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
    private const val KEY_IMPORT_ENGINE = "import_pending_engine"

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
    private const val KEY_NTFY_ENABLED = "ntfy_enabled"
    private const val KEY_NTFY_SETUP_PROMPTED = "ntfy_setup_prompted"
    private const val KEY_NTFY_SINCE = "ntfy_since"
    private const val KEY_CATCHUP_AT = "catchup_last_at"
    private const val KEY_CATCHUP_SUMMARY = "catchup_last_summary"
    private const val KEY_LISTENER_AT = "listener_connected_at"
    private const val KEY_THEME = "theme_palette"
    private const val KEY_SPLASH_VERSION = "splash_version"
    private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_HEALTH = "health_cache"
    private const val KEY_ONBOARDED = "onboarded"
    /** 礼包预置值是否已经写过一次（写过就不再覆盖用户自己的设置）。 */
    private const val KEY_SEED_APPLIED = "seed_applied"
    private const val KEY_HEALTH_AT = "health_cached_at"
    private const val KEY_COLLAPSED_GROUPS = "list_collapsed_groups"
    private const val KEY_EXPANDED_GROUPS = "list_expanded_groups"
    private const val KEY_GROUP_DEFAULT_V2 = "list_group_default_v2"

    /**
     * 识别方式：auto（联网走云端、断网用本地）/ local（只用本地）/ cloud（只用云端）。
     * 空串 = 用户还没选过，首次引导里问一次。
     */
    private const val KEY_OCR_ENGINE = "ocr_engine"

    /**
     * 通知怎么用：off（不碰）/ onDemand（只在用户主动触发时读最近一条）/
     * keyword（命中关键词才提醒）/ auto（自动建）。默认 off，别自作主张。
     */
    private const val KEY_NOTIFY_LEVEL = "notify_level"

    /** 存「被忽略的通知」用的分隔符：\u0001 分条、\u0002 分字段，正常正文里不会出现。 */
    private const val ENTRY_SEP = "\u0001"
    private const val FIELD_SEP = "\u0002"

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

    /**
     * 上次识别的现场。[engine] 决定「怎么把它读回来」：
     * 云端存的是模型输出的 JSON，本地存的是 OCR 原文——两种格式混着按 JSON 解，
     * 本地的结果会被当成「已失效」白丢一次。
     */
    data class PendingImport(
        val uri: String,
        val raw: String,
        val at: Long,
        val engine: String = ENGINE_CLOUD
    )

    fun pendingImport(context: Context): PendingImport? {
        val p = sp(context)
        val raw = p.getString(KEY_IMPORT_RAW, "").orEmpty()
        if (raw.isBlank()) return null
        return PendingImport(
            uri = p.getString(KEY_IMPORT_URI, "").orEmpty(),
            raw = raw,
            at = p.getLong(KEY_IMPORT_AT, 0L),
            engine = p.getString(KEY_IMPORT_ENGINE, ENGINE_CLOUD).orEmpty().ifBlank { ENGINE_CLOUD }
        )
    }

    fun savePendingImport(
        context: Context,
        uri: String,
        raw: String,
        engine: String = ENGINE_CLOUD
    ) {
        if (raw.isBlank()) return
        sp(context).edit()
            .putString(KEY_IMPORT_URI, uri)
            .putString(KEY_IMPORT_RAW, raw)
            .putLong(KEY_IMPORT_AT, System.currentTimeMillis())
            .putString(KEY_IMPORT_ENGINE, engine)
            .apply()
    }

    fun clearPendingImport(context: Context) {
        sp(context).edit()
            .remove(KEY_IMPORT_URI)
            .remove(KEY_IMPORT_RAW)
            .remove(KEY_IMPORT_AT)
            .remove(KEY_IMPORT_ENGINE)
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

    /**
     * ntfy 模式总开关（3.17 起独立于「有没有填主题」）。没写过这个值的老用户按
     * 「填了主题就是开」推断，升级后行为不变。
     */
    fun ntfyEnabled(context: Context): Boolean {
        val p = sp(context)
        if (!p.contains(KEY_NTFY_ENABLED)) return ntfyTopic(context).isNotBlank()
        return p.getBoolean(KEY_NTFY_ENABLED, true)
    }

    fun setNtfyEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_NTFY_ENABLED, on).apply()
    }

    /** 首次接好 ntfy（开关开着 + 主题非空）时提示过一次后台设置没有。 */
    fun ntfySetupPrompted(context: Context): Boolean =
        sp(context).getBoolean(KEY_NTFY_SETUP_PROMPTED, false)

    fun setNtfySetupPrompted(context: Context, done: Boolean) {
        sp(context).edit().putBoolean(KEY_NTFY_SETUP_PROMPTED, done).apply()
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

    /** 礼包预置值写过没有；普通安装没有预置值时也会置 true，避免每次启动重复判断。 */
    fun seedApplied(context: Context): Boolean = sp(context).getBoolean(KEY_SEED_APPLIED, false)

    fun setSeedApplied(context: Context, done: Boolean) {
        sp(context).edit().putBoolean(KEY_SEED_APPLIED, done).apply()
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

    /** 是否同意过隐私政策（首次启动必须点过「同意」）。 */
    fun privacyAccepted(context: Context): Boolean =
        sp(context).getBoolean(KEY_PRIVACY_ACCEPTED, false)

    fun setPrivacyAccepted(context: Context, accepted: Boolean) {
        sp(context).edit().putBoolean(KEY_PRIVACY_ACCEPTED, accepted).apply()
    }

    /** 上次播过开屏的版本号；换了版本就再播一次品牌动效。 */
    fun splashVersion(context: Context): String =
        sp(context).getString(KEY_SPLASH_VERSION, "").orEmpty()

    fun setSplashVersion(context: Context, version: String) {
        sp(context).edit().putString(KEY_SPLASH_VERSION, version).apply()
    }

    /** 明暗模式：system（跟随系统）/ light / dark。 */
    fun themeMode(context: Context): String =
        sp(context).getString(KEY_THEME_MODE, "system").orEmpty().ifBlank { "system" }

    fun setThemeMode(context: Context, mode: String) {
        sp(context).edit().putString(KEY_THEME_MODE, mode).apply()
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

    /** 列表里哪些分组被用户显式收起来了（默认状态由 DayGroups 决定）。 */
    fun collapsedGroups(context: Context): Set<String> =
        readGroupSet(context, KEY_COLLAPSED_GROUPS)

    fun setCollapsedGroups(context: Context, labels: Set<String>) {
        sp(context).edit().putString(KEY_COLLAPSED_GROUPS, labels.joinToString("\u0001")).apply()
    }

    /** 列表里哪些分组被用户显式展开过（默认收起的分组靠它记住「我展开过」）。 */
    fun expandedGroups(context: Context): Set<String> =
        readGroupSet(context, KEY_EXPANDED_GROUPS)

    fun setExpandedGroups(context: Context, labels: Set<String>) {
        sp(context).edit().putString(KEY_EXPANDED_GROUPS, labels.joinToString("\u0001")).apply()
    }

    private fun readGroupSet(context: Context, key: String): Set<String> {
        val raw = sp(context).getString(key, null) ?: return emptySet()
        return raw.split('\u0001').filter { it.isNotBlank() }.toSet()
    }

    /**
     * 一次性迁移：分组默认状态改成「今天/明天展开、其余收起」以后，
     * 老的收展记录（老逻辑下默认全展开）会跟新默认打架，清掉重来一次。
     */
    fun migrateGroupDefaults(context: Context) {
        val prefs = sp(context)
        if (prefs.getBoolean(KEY_GROUP_DEFAULT_V2, false)) return
        prefs.edit()
            .remove(KEY_COLLAPSED_GROUPS)
            .remove(KEY_EXPANDED_GROUPS)
            .putBoolean(KEY_GROUP_DEFAULT_V2, true)
            .apply()
    }

    // ---------------- 识别方式（用户自己选，不替他决定） ----------------

    const val ENGINE_AUTO = "auto"
    const val ENGINE_LOCAL = "local"
    const val ENGINE_CLOUD = "cloud"

    /** 离线大模型（Gemma 3 1B，跑在手机上）：先 OCR 出文字，再让模型理解成待办。 */
    const val ENGINE_LLM = "llm"

    /** auto / local / cloud；空串表示还没选过，首次引导要问。 */
    fun ocrEngine(context: Context): String =
        sp(context).getString(KEY_OCR_ENGINE, "").orEmpty()

    fun setOcrEngine(context: Context, engine: String) {
        sp(context).edit().putString(KEY_OCR_ENGINE, engine).apply()
    }

    fun ocrEngineChosen(context: Context): Boolean = ocrEngine(context).isNotBlank()

    // ---------------- 通知档位 ----------------

    /** off / onDemand / keyword / auto。默认 off。 */
    fun notifyLevel(context: Context): String =
        sp(context).getString(KEY_NOTIFY_LEVEL, "off").orEmpty().ifBlank { "off" }

    fun setNotifyLevel(context: Context, level: String) {
        sp(context).edit().putString(KEY_NOTIFY_LEVEL, level).apply()
    }

    // ---------------- 离线大模型 ----------------

    private const val KEY_MODEL_DOWNLOAD_ID = "local_model_download_id"

    /** 系统 DownloadManager 的任务 id；-1 表示没有在下的任务。 */
    fun modelDownloadId(context: Context): Long =
        sp(context).getLong(KEY_MODEL_DOWNLOAD_ID, -1L)

    fun setModelDownloadId(context: Context, id: Long) {
        sp(context).edit().putLong(KEY_MODEL_DOWNLOAD_ID, id).apply()
    }

    // ---------------- 识别链路：本地规则（常亮）+ 云端模型 / 本地模型 ----------------

    private const val KEY_CLOUD_MODEL = "cloud_model_enabled"
    private const val KEY_LOCAL_MODEL = "local_model_enabled"

    /**
     * 云端模型开没开。没写过这个值时按老版本的「识别方式」推断：
     * 老用户选过「只用本地 / 本地模型」就默认关，其余（自动/云端/没选过）默认开。
     */
    fun cloudModelEnabled(context: Context): Boolean {
        val p = sp(context)
        if (p.contains(KEY_CLOUD_MODEL)) return p.getBoolean(KEY_CLOUD_MODEL, true)
        val legacy = ocrEngine(context)
        return legacy != ENGINE_LOCAL && legacy != ENGINE_LLM
    }

    fun setCloudModelEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_CLOUD_MODEL, on).apply()
    }

    /** 本地模型开没开；老版本选过「本地模型」的算开。 */
    fun localModelEnabled(context: Context): Boolean {
        val p = sp(context)
        if (p.contains(KEY_LOCAL_MODEL)) return p.getBoolean(KEY_LOCAL_MODEL, false)
        return ocrEngine(context) == ENGINE_LLM
    }

    fun setLocalModelEnabled(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_LOCAL_MODEL, on).apply()
    }

    // ---------------- 云端服务地址 / 本地模型选择 ----------------

    private const val KEY_CLOUD_BASE_URL = "cloud_base_url"
    private const val KEY_LOCAL_MODEL_ID = "local_model_id"

    /** 云端服务地址，默认 DeepSeek；换成任何 OpenAI 兼容地址都行。 */
    fun cloudBaseUrl(context: Context): String =
        sp(context).getString(KEY_CLOUD_BASE_URL, com.bellnote.ai.VisionExtractor.DEFAULT_BASE_URL)
            .orEmpty()
            .trim()
            .ifBlank { com.bellnote.ai.VisionExtractor.DEFAULT_BASE_URL }

    fun setCloudBaseUrl(context: Context, url: String) {
        sp(context).edit().putString(KEY_CLOUD_BASE_URL, url.trim()).apply()
    }

    /** 本地模型选了哪一个（[com.bellnote.ai.LocalModels] 的 id）。 */
    fun localModelId(context: Context): String =
        sp(context).getString(KEY_LOCAL_MODEL_ID, com.bellnote.ai.LocalModels.DEFAULT_ID)
            .orEmpty()
            .ifBlank { com.bellnote.ai.LocalModels.DEFAULT_ID }

    fun setLocalModelId(context: Context, id: String) {
        sp(context).edit().putString(KEY_LOCAL_MODEL_ID, id).apply()
    }
}
