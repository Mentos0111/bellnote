package com.bellnote.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 反馈包的「内容」部分：只做纯文本拼装，不碰 Android API，方便单测。
 *
 * 分成两层：
 *  - 这里：Report（纯数据）→ render / [bundleTexts]（纯文本）；
 *  - FeedbackBundle：从手机里把 Report 采出来、按 [bundleTexts] 写入 zip。
 *
 * **脱敏红线（3.21.0 起）**：
 *  - API Key、ntfy 主题只报「有没有配、多少位」，任何字符都不进包；
 *  - 待办/推送的标题和正文不进包，只保留时间、方向、来源、结果这类结构化信息；
 *  - 崩溃记录只保留异常类名与堆栈帧（异常消息丢弃），并统一过 [redact]；
 *    **完全不采集 logcat**，也不收集任何消息正文/说明。
 *
 * 这个包默认只给可信接收方；不要直接贴到公开 Issue。
 */
object Diagnostics {

    /** 消息记录最多带这么多条。 */
    const val MAX_MESSAGES = 200

    /** 说明字段最多留这么多字（本身已脱敏）。 */
    const val MAX_BODY_CHARS = 400

    /** 页面预览只显示前面这些字，完整内容在 zip 里。 */
    const val PREVIEW_CHARS = 6_000

    /** 消息记录里的一条（3.21.0 起不再携带标题与正文）。 */
    data class Message(
        val at: Long,
        val direction: String,
        val source: String,
        val result: String,
        /** 原因码（MISSING_TIME 等），不带自由文本；成功时为空。 */
        val reason: String = ""
    )

    /** 「最近没写进日历的推送」：只留时间与原因。 */
    data class Notice(val at: Long, val reason: String)

    data class Report(
        val generatedAt: Long,
        val appName: String,
        val versionName: String,
        val versionCode: Long,
        val packageName: String,
        val device: String,
        val android: String,
        val locale: String,
        val timezone: String,
        val settings: List<Pair<String, String>> = emptyList(),
        val permissions: List<Pair<String, String>> = emptyList(),
        val stats: List<Pair<String, String>> = emptyList(),
        val messages: List<Message> = emptyList(),
        val notices: List<Notice> = emptyList(),
        val crash: String? = null,
        /** 需要从自由文本里抹掉的真实值（Key、主题等），不会出现在输出里。 */
        val secrets: List<String> = emptyList()
    )

    /**
     * 凭据打码：**不保留任何字符**，只说明「有没有配、多少位」。
     * 以前的「前 4 + 后 4」会把密钥的可辨认片段带出去，3.21.0 起不再这么做。
     */
    fun maskSecret(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return "（未填）"
        return "（已配置，共 ${value.length} 位，内容不记录）"
    }

    /**
     * 自由文本脱敏：用于崩溃堆栈。
     *
     * 处理顺序有讲究——**先砍 URL**（只留协议+主机），否则后面用「（已隐藏）」替换主题时
     * 会把 URL 切断，残留的 `?since=…` 等参数反而漏出去；再抹真实值与常见模式串。
     */
    fun redact(text: String, secrets: List<String> = emptyList()): String {
        if (text.isBlank()) return text
        var out = text
        // URL：只保留协议 + 主机，路径与查询参数一律省略
        out = Regex("(?i)\\bhttps?://[^\\s\"'）)\\]，。]+").replace(out) { match ->
            val url = match.value
            val schemeEnd = url.indexOf("://")
            val afterScheme = if (schemeEnd >= 0) url.substring(schemeEnd + 3) else url
            val host = afterScheme.substringBefore('/').substringBefore('?')
            val scheme = if (schemeEnd >= 0) url.substring(0, schemeEnd + 3) else ""
            "$scheme$host/（路径与参数已省略）"
        }
        for (secret in secrets) {
            val value = secret.trim()
            if (value.length >= 6) out = out.replace(value, "（已隐藏）")
        }
        // 常见密钥形态
        out = Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}").replace(out, "Bearer （已隐藏）")
        out = Regex("\\bsk-[A-Za-z0-9._\\-]{6,}").replace(out, "sk-（已隐藏）")
        // ntfy 主题样式：todo-xxxxxxx / 长十六进制串
        out = Regex("(?i)\\btodo-[A-Za-z0-9_\\-]{4,}").replace(out, "todo-（已隐藏）")
        out = Regex("\\b[0-9a-f]{20,}\\b").replace(out, "（已隐藏）")
        // topic=xxx、key=xxx 这类查询参数（如果 URL 已经处理掉，这里兜底纯文本形式）
        out = Regex("(?i)\\b(topic|key|token|password|secret)=[^\\s&\"'）)]+")
            .replace(out, "$1=（已隐藏）")
        return out
    }

    /**
     * 单独落盘的附件（crash.txt）统一走这里：
     * 与 diagnostics.txt 用同一套脱敏规则，避免「主文件干净、附件漏词」的缝。
     */
    fun fileText(report: Report, raw: String): String = redact(raw, report.secrets)

    /**
     * **zip 里真正要写入的文本**：FeedbackBundle 直接消费这个结果落盘，
     * 所以对它做 canary 单测＝证明 zip 里每个文本附件的内容。
     *
     * 返回顺序固定：diagnostics.txt → crash.txt（没有崩溃记录时只有前者）。
     */
    fun bundleTexts(report: Report): List<Pair<String, String>> = buildList {
        add("diagnostics.txt" to render(report))
        report.crash?.takeIf { it.isNotBlank() }
            ?.let { add("crash.txt" to crashText(report, it)) }
    }

    /**
     * 崩溃附件只保留**结构化堆栈**：异常类名 + `at …` 帧 + `Caused by: 类名`。
     * 异常消息（冒号后面那段）可能是解析失败时的用户原文，一律丢掉；
     * 最后再走一遍 [redact] 兜底 URL/凭据。
     */
    fun crashText(report: Report, raw: String): String {
        val kept = raw.lineSequence().mapNotNull { line ->
            val t = line.trimStart()
            when {
                t.startsWith("at ") || t.startsWith("... ") -> line
                t.startsWith("Caused by:") || t.startsWith("Suppressed:") -> {
                    val prefix = t.substringBefore(':') + ":"
                    val cls = t.substringAfter(':').trim().substringBefore(':').trim()
                    "    $prefix ${cls.takeWhile { it.isLetterOrDigit() || it == '.' || it == '$' }}"
                }
                else -> {
                    val cls = t.substringBefore(':').trim()
                    if (cls.contains("Exception") || cls.contains("Error")) "    $cls" else null
                }
            }
        }
        return redact(kept.joinToString("\n"), report.secrets)
    }

    fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))

    fun render(report: Report): String = buildString {
        appendLine("Bellnote 反馈包")
        appendLine("生成时间：${stamp(report.generatedAt)}（${report.timezone}）")
        appendLine("说明：本包不含待办标题与正文；API Key 与 ntfy 主题只报配置状态；URL 已去掉路径与参数。")
        appendLine()
        appendLine("【应用】")
        appendLine("名称：${report.appName}")
        appendLine("版本：${report.versionName}（versionCode ${report.versionCode}）")
        appendLine("包名：${report.packageName}")
        appendLine("设备：${report.device}")
        appendLine("系统：${report.android}")
        appendLine("语言：${report.locale}")

        section("设置快照", report.settings)
        section("权限状态", report.permissions)
        section("统计", report.stats)

        appendLine()
        appendLine("【最近的消息记录】")
        if (report.messages.isEmpty()) {
            appendLine("（没有记录）")
        } else {
            appendLine("共 ${report.messages.size} 条，最新的在前（只保留结构与结果，不含标题/正文）：")
            report.messages.take(MAX_MESSAGES).forEach { message ->
                appendLine(
                    "${stamp(message.at)} · ${message.direction} · " +
                        "${message.source.ifBlank { "-" }} · ${message.result}"
                )
                if (message.reason.isNotBlank()) {
                    appendLine("    原因：${com.bellnote.data.ReasonCode.display(message.reason)}")
                }
            }
        }

        appendLine()
        appendLine("【最近没写进日历的推送】")
        if (report.notices.isEmpty()) {
            appendLine("（没有）")
        } else {
            report.notices.forEach { notice ->
                appendLine("${stamp(notice.at)} · ${notice.reason}（内容不记录）")
            }
        }

        appendLine()
        appendLine("【崩溃记录】")
        val crash = report.crash?.trim()
        if (crash.isNullOrEmpty()) {
            appendLine("（没有崩溃记录）")
        } else {
            appendLine(clip(crashText(report, crash), 18_000))
        }

        appendLine()
        appendLine("【日志】")
        appendLine("（本包不收集 logcat；需要日志时请用 adb 或让开发者远程排查）")
    }

    /** 交给界面预览：太长就截断，别让 TextView 撑爆。 */
    fun previewOf(full: String): String {
        if (full.length <= PREVIEW_CHARS) return full
        return full.take(PREVIEW_CHARS) + "\n\n……（预览只显示前 $PREVIEW_CHARS 字，完整内容在 zip 里）"
    }

    private fun StringBuilder.section(title: String, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        appendLine()
        appendLine("【$title】")
        rows.forEach { (key, value) -> appendLine("$key：$value") }
    }

    private fun clip(text: String, max: Int, indent: String = ""): String {
        val one = text.replace("\r\n", "\n").trim()
        val body = if (one.length <= max) one else one.take(max) + "…（已截断，原文 ${one.length} 字）"
        return body.replace("\n", "\n$indent")
    }
}
