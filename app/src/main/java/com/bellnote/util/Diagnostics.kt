package com.bellnote.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 反馈包的「内容」部分：只做纯文本拼装，不碰 Android API，方便单测。
 *
 * 分成两层：
 *  - 这里：Report（纯数据）→ render（纯文本）；
 *  - FeedbackBundle：从手机里把 Report 采出来、写成 zip、交给分享面板。
 *
 * 排查时最缺的是「这台机器上到底是什么状态」，所以版本 / 机型 / 权限 / 设置快照
 * 一律放在最前面，日志在后。默认带原文（标题、推送正文），只有开发者会收到；
 * API Key、主题这类凭据一律打码。
 */
object Diagnostics {

    /** 消息记录最多带这么多条。 */
    const val MAX_MESSAGES = 200

    /** 单条正文最多留这么多字，避免一条巨长的推送把整包撑爆。 */
    const val MAX_BODY_CHARS = 1_200

    /** logcat 只留本 App 最近的这些字。 */
    const val MAX_LOGCAT_CHARS = 20_000

    /** 页面预览只显示前面这些字，完整内容在 zip 里。 */
    const val PREVIEW_CHARS = 6_000

    /** 消息记录里的一条（direction / result 已由调用方翻成中文）。 */
    data class Message(
        val at: Long,
        val direction: String,
        val source: String,
        val title: String,
        val body: String,
        val result: String,
        val detail: String
    )

    /** 「最近没写进日历的推送」旧摘要。 */
    data class Notice(val at: Long, val reason: String, val text: String)

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
        val logcat: String? = null
    )

    /**
     * 凭据打码：留首尾各 4 位，中间用省略号。太短的一律不露。
     * 空值写明「未填」，排查时「没配」本身也是有效信息。
     */
    fun maskSecret(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return "（未填）"
        if (value.length <= 8) return "••••（共 ${value.length} 位）"
        return value.take(4) + "…" + value.takeLast(4) + "（共 ${value.length} 位）"
    }

    fun stamp(at: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(at))

    fun render(report: Report): String = buildString {
        appendLine("Bellnote 反馈包")
        appendLine("生成时间：${stamp(report.generatedAt)}（${report.timezone}）")
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
            appendLine("共 ${report.messages.size} 条，最新的在前：")
            report.messages.take(MAX_MESSAGES).forEach { message ->
                appendLine()
                appendLine(
                    "${stamp(message.at)} · ${message.direction} · " +
                        "${message.source.ifBlank { "-" }} · ${message.result}"
                )
                if (message.title.isNotBlank()) {
                    appendLine("    标题：${clip(message.title, MAX_BODY_CHARS, indent = "          ")}")
                }
                if (message.body.isNotBlank()) {
                    appendLine("    正文：${clip(message.body, MAX_BODY_CHARS, indent = "          ")}")
                }
                if (message.detail.isNotBlank()) {
                    appendLine("    说明：${clip(message.detail, MAX_BODY_CHARS, indent = "          ")}")
                }
            }
        }

        appendLine()
        appendLine("【最近没写进日历的推送】")
        if (report.notices.isEmpty()) {
            appendLine("（没有）")
        } else {
            report.notices.forEach { notice ->
                appendLine("${stamp(notice.at)} · ${notice.reason} · ${clip(notice.text, 300)}")
            }
        }

        appendLine()
        appendLine("【崩溃记录】")
        val crash = report.crash?.trim()
        if (crash.isNullOrEmpty()) appendLine("（没有崩溃记录）") else appendLine(clip(crash, 18_000))

        appendLine()
        appendLine("【logcat（仅本 App 能读到的部分）】")
        val logcat = report.logcat?.trim()
        if (logcat.isNullOrEmpty()) {
            appendLine("（系统不允许 App 读 logcat，或没有内容）")
        } else {
            appendLine(clip(logcat, MAX_LOGCAT_CHARS))
        }
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
