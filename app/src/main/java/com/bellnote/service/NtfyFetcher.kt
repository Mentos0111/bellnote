package com.bellnote.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bellnote.App
import com.bellnote.R
import com.bellnote.data.MessageLog
import com.bellnote.data.Prefs
import com.bellnote.parser.MiniJson
import com.bellnote.ui.MainActivity
import com.bellnote.util.Permissions
import com.bellnote.util.NtfyEndpoint
import com.bellnote.util.TimeFormat
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直接从 ntfy 服务器补查消息。
 *
 * 为什么需要它：Bellnote 只监听 io.heckel.ntfy 的通知，ntfy App 被 realme 冻结、被杀、断网、
 * 通知被关的时候，消息就永久丢了 —— 2026-11-11 22:20 那次 4 条日历指令就是这么没的。
 * 这里用 ntfy 的缓存回查（`/json?poll=1&since=…`）把漏掉的消息补回来，走和通知完全相同的
 * [TodoIngestor] 路径，所以删除/新建/严格模式的规则完全一致。
 *
 * 设置里填了主题才启用；留空 = 保持旧行为（只靠通知），不联网、不打扰。
 */
object NtfyFetcher {

    private const val TAG = "NtfyFetcher"

    /** 默认往回补多久：2 小时。够覆盖「睡一觉手机被冻结」的情况，又不会翻出一堆旧账。 */
    const val DEFAULT_WINDOW_SECONDS = 2L * 60 * 60

    private const val TIMEOUT_MS = 10_000
    private const val MAX_MESSAGES = 30
    private const val NOTIFY_ID = 1003

    @Volatile
    private var running = false

    private fun tryAcquire(): Boolean {
        synchronized(this) {
            if (running) return false
            running = true
            return true
        }
    }

    data class Message(
        val id: String,
        val timeSeconds: Long,
        val title: String,
        val text: String
    )

    data class Result(
        val fetched: Int = 0,
        val wrote: Int = 0,
        val dropped: Int = 0,
        val failed: String? = null,
        val skipped: Boolean = false
    ) {
        val changed: Boolean get() = wrote > 0 || dropped > 0

        fun summary(): String = when {
            skipped -> "ntfy 模式没开或没填主题，跳过补查"
            failed != null -> "补查失败：$failed"
            fetched == 0 -> "补查：没有新消息"
            else -> "补查：拉到 $fetched 条，写进日历 $wrote 条，没写 $dropped 条"
        }
    }

    /**
     * 后台跑一次，不阻塞调用方；已经在跑就跳过。
     * @param minGapMillis 距上次补查不到这么久就干脆不跑（App 启动 + 打开首页会连着触发两次）
     */
    fun catchUpInBackground(
        context: Context,
        notify: Boolean = true,
        minGapMillis: Long = 0L
    ) {
        if (running) return
        val app = context.applicationContext
        if (minGapMillis > 0L) {
            val gap = System.currentTimeMillis() - Prefs.lastCatchUpAt(app)
            if (gap in 0 until minGapMillis) return
        }
        Thread {
            try {
                val result = catchUp(app)
                if (notify && result.changed) notifyChanged(app, result)
            } catch (e: Exception) {
                Log.w(TAG, "后台补查失败", e)
            }
        }.start()
    }

    /**
     * 同步补查一次（调用方自己保证不在主线程）。
     * since 取「上次补查到的时间」和「now - window」里更近的那个，避免重放太久以前的。
     */
    fun catchUp(context: Context, windowSeconds: Long = DEFAULT_WINDOW_SECONDS): Result {
        val app = context.applicationContext
        if (!Prefs.ntfyEnabled(app)) {
            Prefs.setLastCatchUpSummary(app, "ntfy 模式已关闭，跳过补查")
            return Result(skipped = true)
        }
        val topics = Prefs.ntfyTopics(app).filter { it.topic.isNotBlank() }
        if (topics.isEmpty()) {
            Prefs.setLastCatchUpSummary(app, "还没填 ntfy 主题，跳过补查")
            return Result(skipped = true)
        }
        if (!tryAcquire()) return Result(skipped = true)

        try {
            val nowSeconds = System.currentTimeMillis() / 1000
            val floor = nowSeconds - windowSeconds
            var fetched = 0
            var wrote = 0
            var dropped = 0
            var failedTopics = 0
            var lastError: String? = null

            for (t in topics) {
                val stored = t.since
                val since = if (stored in (floor + 1)..nowSeconds) stored else floor
                val messages = try {
                    fetch(app, t.topic, since)
                } catch (e: Exception) {
                    failedTopics++
                    lastError = e.message ?: e.javaClass.simpleName
                    Log.w(TAG, "补查失败 topic=${t.topic}", e)
                    continue
                }
                Log.i(TAG, "补查 topic=${t.topic} since=$since 拉到 ${messages.size} 条")

                // 只处理比上次更晚的（服务器 since 是闭区间的，边界那条会重复回来）
                val fresh = messages
                    .filter { stored <= 0 || it.timeSeconds > stored }
                    .takeLast(MAX_MESSAGES)

                var newest = stored
                for (message in fresh) {
                    // 用消息自己的时间做「重放保护」：旧删除指令不许删后来新建的事件
                    val outcome = TodoIngestor.handle(
                        app,
                        message.title,
                        message.text,
                        messageTime = if (message.timeSeconds > 0) {
                            message.timeSeconds * 1000
                        } else {
                            System.currentTimeMillis()
                        },
                        source = "补查"
                    )
                    wrote += outcome.wrote + outcome.updated
                    dropped += outcome.dropped
                    if (message.timeSeconds > newest) newest = message.timeSeconds
                }
                fetched += fresh.size

                // 每条主题各自推进游标，避免互相污染
                if (fresh.isNotEmpty()) {
                    Prefs.setNtfySinceFor(app, t.topic, newest)
                } else if (stored <= 0) {
                    Prefs.setNtfySinceFor(app, t.topic, nowSeconds)
                }
            }

            if (failedTopics > 0 && wrote == 0 && dropped == 0) {
                val failed = Result(failed = lastError ?: "$failedTopics 个主题没拉到")
                Prefs.setLastCatchUpAt(app, System.currentTimeMillis())
                Prefs.setLastCatchUpSummary(app, failed.summary())
                Log.w(TAG, failed.summary())
                return failed
            }

            val result = Result(fetched = fetched, wrote = wrote, dropped = dropped)
            Prefs.setLastCatchUpAt(app, System.currentTimeMillis())
            Prefs.setLastCatchUpSummary(app, result.summary())
            Log.i(TAG, result.summary())
            return result
        } catch (e: Exception) {
            val result = Result(failed = e.message ?: e.javaClass.simpleName)
            Prefs.setLastCatchUpAt(app, System.currentTimeMillis())
            Prefs.setLastCatchUpSummary(app, result.summary())
            MessageLog.log(
                app,
                MessageLog.DIR_IN,
                "补查",
                "拉取失败",
                "服务器 ${Prefs.ntfyServer(app)}",
                MessageLog.RESULT_FAILED,
                result.summary()
            )
            Log.w(TAG, "补查失败", e)
            return result
        } finally {
            running = false
        }
    }

    private fun fetch(context: Context, topic: String, sinceSeconds: Long): List<Message> {
        val server = Prefs.ntfyServer(context)
        val endpoint = NtfyEndpoint.check(server)
        if (!endpoint.ok) throw IllegalStateException(endpoint.reason ?: "服务器地址不对")
        val url = "${endpoint.normalized}/${topic.trim('/')}/json?poll=1&since=$sinceSeconds"
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/x-ndjson")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("ntfy 返回 HTTP $code")
            }
            val raw = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseJsonLines(raw)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * ntfy 的 `/json?poll=1` 返回的是「一行一个 JSON 对象」（ndjson）。
     * 只留 event=message 且有正文的；keepalive / open / message_delete 这些系统事件跳过。
     * 纯函数，已单测（注意 MiniJson 是 App 内部实现，不依赖 org.json，能在 JVM 上跑）。
     */
    fun parseJsonLines(raw: String): List<Message> {
        val out = mutableListOf<Message>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val fields = MiniJson.flatScalars(MiniJson.parse(trimmed))
            if (fields.isEmpty()) continue
            val event = fields["event"]?.trim()?.lowercase().orEmpty()
            if (event.isNotEmpty() && event != "message") continue
            val text = fields["message"].orEmpty()
            if (text.isBlank()) continue
            out.add(
                Message(
                    id = fields["id"].orEmpty(),
                    timeSeconds = fields["time"]?.toLongOrNull() ?: 0L,
                    title = fields["title"].orEmpty(),
                    text = text
                )
            )
        }
        return out
    }

    /**
     * 补进待办后弹一条通知。前台服务 / 闹钟这两条后台路径自己调它，
     * 「打开 App 时补查」则由 [catchUpInBackground] 调。
     */
    fun notifyChanged(context: Context, result: Result) {
        if (!result.changed) return
        if (!Permissions.hasPostNotifications(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = "收到 ${result.wrote} 条新待办"
        val body = buildString {
            append("已写进日历")
            if (result.dropped > 0) append("；另有 ${result.dropped} 条没写进去（消息记录里能看到原因）")
            append("\n时间 ${TimeFormat.full(System.currentTimeMillis())}")
        }
        val notification = NotificationCompat.Builder(context, App.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText("已写进日历")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "发补查通知失败", e)
        }
    }
}
