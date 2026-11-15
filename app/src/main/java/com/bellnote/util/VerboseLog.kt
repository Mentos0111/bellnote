package com.bellnote.util

import android.content.Context
import com.bellnote.data.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 按需详细日志（默认关闭，24 小时自动过期）。
 *
 * 为什么自己写文件：Android 不允许 App 读系统 logcat，而且系统缓冲区会被清。
 * 这里只写**本应用自己的**诊断文件：`files/diag/verbose.log`，内容可能包含
 * 消息标题/正文/URL，所以：
 *  - 默认关；打开时写 `Prefs.verboseLogSince = now`
 *  - 超过 24 小时自动视为关闭，并在下次访问时删除文件
 *  - 关闭开关立即删除文件
 *  - 写入时裁掉超过 24 小时的行，文件过大时只保留最近的行（轮转）
 *
 * 打包诊断默认**不包含**它，只有用户在反馈页显式勾选才会进 ZIP。
 */
object VerboseLog {

    private const val DIR = "diag"
    private const val NAME = "verbose.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val KEEP_LINES = 2000
    private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    /** 保留窗口：24 小时。 */
    const val TTL_MS = 24 * 60 * 60 * 1000L

    fun file(context: Context): File = File(File(context.filesDir, DIR), NAME)

    /** 开关是否真正生效（开着且未超过 24 小时）。过期会顺手关掉并删文件。 */
    fun isEnabled(context: Context): Boolean {
        val since = Prefs.verboseLogSince(context)
        if (since <= 0L) return false
        if (isExpired(since, System.currentTimeMillis())) {
            Prefs.setVerboseLogSince(context, 0L)
            file(context).delete()
            return false
        }
        return true
    }

    /** 剩余有效时间（毫秒）；关着返回 0。 */
    fun remainingMs(context: Context): Long {
        if (!isEnabled(context)) return 0L
        val since = Prefs.verboseLogSince(context)
        return (since + TTL_MS - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 纯函数：给定开启时刻与当前时刻，是否已超过 24 小时。 */
    internal fun isExpired(since: Long, now: Long): Boolean =
        since > 0L && now - since > TTL_MS

    /** 纯函数：只保留 24 小时内、且首列是合法时间戳的行。 */
    internal fun keepRecent(lines: List<String>, now: Long): List<String> {
        val cutoff = now - TTL_MS
        return lines.filter { line ->
            val t = lineTime(line)
            t >= cutoff && t > 0L && line.isNotBlank()
        }
    }

    fun enable(context: Context) {
        Prefs.setVerboseLogSince(context, System.currentTimeMillis())
    }

    fun disable(context: Context) {
        Prefs.setVerboseLogSince(context, 0L)
        file(context).delete()
    }

    /** 追加一行；未开启或已过期时什么都不做。 */
    @Synchronized
    fun append(context: Context, tag: String, text: String) {
        if (!isEnabled(context)) return
        val message = text.replace("\r", " ").replace("\n", " ").trim()
        if (message.isEmpty()) return
        val line = "${STAMP.format(Date())} [$tag] ${message.take(600)}"
        val f = file(context)
        try {
            f.parentFile?.mkdirs()
            f.appendText(line + "\n", Charsets.UTF_8)
            prune(context, f)
        } catch (e: Exception) {
            // 详细日志写失败绝不能影响主流程
        }
    }

    /** 读取当前保留的内容；未开启或过期返回 null。 */
    fun read(context: Context): String? {
        if (!isEnabled(context)) return null
        val f = file(context)
        if (!f.exists()) return null
        return try {
            f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /** 超过 24h 的行裁掉；文件太大时只留最近 KEEP_LINES 行。 */
    private fun prune(context: Context, f: File) {
        if (!f.exists()) return
        if (f.length() <= MAX_BYTES) {
            // 仍然按时间裁一遍（每次写入顺便做，成本很低）
            val all = f.readLines(Charsets.UTF_8)
            val kept = keepRecent(all, System.currentTimeMillis())
            if (kept.size != all.size) {
                f.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"), Charsets.UTF_8)
            }
            return
        }
        val kept = f.readLines(Charsets.UTF_8).takeLast(KEEP_LINES)
        f.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private fun lineTime(line: String): Long = try {
        if (line.length < 19) 0L else STAMP.parse(line.substring(0, 19))?.time ?: 0L
    } catch (e: Exception) {
        0L
    }
}
