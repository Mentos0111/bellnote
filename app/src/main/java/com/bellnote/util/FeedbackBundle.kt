package com.bellnote.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.bellnote.data.MessageLog
import com.bellnote.data.Prefs
import com.bellnote.data.TodoStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 「一键导出日志并发送」：
 *
 * 1. 把版本 / 机型 / 权限 / 设置 / 消息记录 / 崩溃 采集出来（纯文本，不采集 logcat）；
 * 2. 打成 zip 放进 cacheDir/feedback；
 * 3. 用系统分享面板发出去（微信、QQ、邮件都行），不绑定某一个 App。
 *
 * 为什么不用「点一下直接发邮件」：手机会员很可能没配邮件 App，分享面板更稳；
 * 邮件那条路留作备选（mail()）。
 */
object FeedbackBundle {

    private const val DIR = "feedback"
    private const val KEEP = 5

    data class BundleFile(val file: File, val text: String)

    /** 预览 / 复制用：只采集不写文件。 */
    fun preview(context: Context): String = Diagnostics.render(collect(context))

    /** 生成 zip。会清掉更早的包，只留最近几个，别把缓存目录堆满。 */
    fun build(context: Context, includeVerbose: Boolean = false): BundleFile {
        // 采集只做一次：崩溃记录读盘，采两遍既慢又可能两次内容不一样
        val report = collect(context)
        val dir = directory(context)
        prune(dir)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date())
        val file = File(dir, "bellnote-feedback-$stamp.zip")
        // 文本附件的唯一来源：Diagnostics.bundleTexts（脱敏逻辑在那里，可被单测用 canary 覆盖）
        val verbose = if (includeVerbose) VerboseLog.read(context) else null
        val texts = zipEntries(report, includeVerbose, verbose)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            texts.forEach { (name, body) -> put(zip, name, body) }
            put(zip, "README.txt", readme(context, includeVerbose))
        }
        return BundleFile(file, texts.first { it.first == "diagnostics.txt" }.second)
    }

    /** 分享面板：zip 直接丢给微信 / QQ / 邮件。 */
    fun share(context: Context, file: File, subject: String): Boolean = try {
        val uri = uriOf(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            // 有些 ROM 只认 clipData 里的 URI，EXTRA_STREAM 上的授权会丢
            clipData = ClipData.newRawUri("bellnote-feedback", uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(
                Intent.EXTRA_TEXT,
                "Bellnote 反馈包（$subject）\n日志已打包成附件，可以直接发过来。"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "发送日志包")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(chooser)
        true
    } catch (e: Exception) {
        Log.w("FeedbackBundle", "分享日志包失败", e)
        false
    }

    /** 备选：直接跳邮件，zip 当附件。 */
    fun mail(context: Context, file: File, subject: String, address: String): Boolean = try {
        val uri = uriOf(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("bellnote-feedback", uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_EMAIL, arrayOf(address))
            putExtra(Intent.EXTRA_TEXT, "把日志包作为附件发出即可，谢谢。")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // selector 保证只列邮件类 App，而不是把微信也列出来
        send.selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        context.startActivity(
            Intent.createChooser(send, "用邮件发送日志包")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    } catch (e: Exception) {
        Log.w("FeedbackBundle", "邮件发送日志包失败", e)
        false
    }

    fun directory(context: Context): File =
        File(context.cacheDir, DIR).apply { if (!exists()) mkdirs() }

    // ---------------- 采集 ----------------

    private fun collect(context: Context): Diagnostics.Report {
        val app = context.applicationContext
        val info = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "-"
        val versionCode = info?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
        } ?: 0L

        // 3.21.0 起诊断包不再收集标题与正文；只留结构化信息 + 已脱敏的说明
        val messages = MessageLog.recent(app).map {
            Diagnostics.Message(
                at = it.at,
                direction = if (it.fromUser) "收到" else "发出",
                source = it.source,
                result = MessageLog.resultLabel(it.result),
                reason = it.detail
            )
        }
        val notices = Prefs.ignoredNotices(app).map {
            Diagnostics.Notice(at = it.at, reason = it.reason)
        }
        // 需要在自由文本里抹掉的真实值：API Key + 所有 ntfy 主题
        val secrets = buildList {
            Prefs.apiKey(app).takeIf { it.isNotBlank() }?.let { add(it) }
            Prefs.ntfyTopics(app).map { it.topic }.filter { it.isNotBlank() }.forEach { add(it) }
        }

        return Diagnostics.Report(
            generatedAt = System.currentTimeMillis(),
            appName = context.getString(com.bellnote.R.string.app_name),
            versionName = versionName,
            versionCode = versionCode,
            packageName = app.packageName,
            device = "${Build.MODEL} · ${Build.BRAND} · ${DeviceVendor.current().label}" +
                " · ${Build.SUPPORTED_ABIS.firstOrNull() ?: "-"}",
            android = "Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）· ${Build.DISPLAY}",
            locale = Locale.getDefault().toLanguageTag(),
            timezone = TimeZone.getDefault().id,
            settings = settings(app),
            permissions = permissions(app),
            stats = stats(app),
            messages = messages,
            notices = notices,
            crash = CrashLog.read(app),
            secrets = secrets
        )
    }

    private fun settings(context: Context): List<Pair<String, String>> = listOf(
        "配色" to "${ThemeManager.labelOf(Prefs.theme(context))}（${Prefs.theme(context)}）",
        "深浅色" to ThemeManager.nightModeLabel(Prefs.themeMode(context)),
        "识别方式" to buildString {
            append(if (Prefs.cloudModelEnabled(context)) "云端模型开" else "云端模型关")
            append(" / ")
            append(if (Prefs.localModelEnabled(context)) "本地模型开" else "本地模型关")
            append(" / 本地规则常亮")
            if (Prefs.localModelEnabled(context)) {
                append("（模型已下载=").append(com.bellnote.ai.LocalLlm.isReady(context)).append("）")
            }
        },
        "视觉模型" to Prefs.model(context),
        "API Key" to Diagnostics.maskSecret(Prefs.apiKey(context)),
        "自动把推送写进日历" to yesNo(Prefs.autoImport(context)),
        "只收带时间的待办" to yesNo(Prefs.strictParse(context)),
        "通知档位" to Prefs.notifyLevel(context),
        "截图监听" to yesNo(Prefs.shotWatchEnabled(context)),
        "目标日历 id" to Prefs.calendarId(context).toString(),
        "默认提醒（分钟）" to Prefs.remindMinutes(context).joinToString(", "),
        "定时对账" to yesNo(Prefs.reconcileEnabled(context)),
        "对账间隔（分钟）" to Prefs.reconcileInterval(context).toString(),
        "对账有差异时发通知" to yesNo(Prefs.reconcileNotify(context)),
        "上次对账" to at(Prefs.lastReconcileAt(context)),
        "上次对账结果" to Prefs.lastReconcileSummary(context).ifBlank { "（无）" },
        "ntfy 模式" to yesNo(Prefs.ntfyEnabled(context)),
        "ntfy 服务器" to Prefs.ntfyServer(context),
        "ntfy 主题" to Diagnostics.maskSecret(Prefs.ntfyTopic(context)),
        "上次补查" to at(Prefs.lastCatchUpAt(context)),
        "上次补查结果" to Prefs.lastCatchUpSummary(context).ifBlank { "（无）" },
        "首次引导已完成" to yesNo(Prefs.onboardingDone(context))
    )

    private fun permissions(context: Context): List<Pair<String, String>> = listOf(
        "日历读写" to yesNo(Permissions.hasCalendar(context)),
        "通知使用权" to yesNo(Permissions.hasNotificationAccess(context)),
        "通知权限（POST_NOTIFICATIONS）" to yesNo(Permissions.hasPostNotifications(context)),
        "读取相册" to yesNo(Permissions.hasMediaImages(context)),
        "无障碍截屏服务" to yesNo(a11yEnabled(context)),
        "电池优化白名单" to yesNo(ignoringBattery(context))
    )

    private fun stats(context: Context): List<Pair<String, String>> = listOf(
        "本地待办账本" to "${runCatching { TodoStore(context).count() }.getOrDefault(0)} 条",
        "消息记录库" to "${MessageLog.count(context)} 条（上限 ${MessageLog.MAX}）",
        "崩溃记录" to if (CrashLog.read(context) == null) "无" else "有（见崩溃记录）"
    )

    private fun a11yEnabled(context: Context): Boolean = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    private fun ignoringBattery(context: Context): Boolean = try {
        val power = context.getSystemService(PowerManager::class.java)
        power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (e: Exception) {
        false
    }

    // ---------------- 工具 ----------------

    /**
     * zip 里的文本条目（不含 README）：纯函数，便于单测「勾选才带 verbose.log」。
     * includeVerbose=true 且详细日志非空时才追加 verbose.log；其余条目始终来自 Diagnostics。
     */
    internal fun zipEntries(
        report: Diagnostics.Report,
        includeVerbose: Boolean,
        verboseText: String?
    ): List<Pair<String, String>> {
        val base = Diagnostics.bundleTexts(report)
        if (!includeVerbose) return base
        val verbose = verboseText?.takeIf { it.isNotBlank() } ?: return base
        return base + ("verbose.log" to verbose)
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun readme(context: Context, includeVerbose: Boolean = false): String = buildString {
        appendLine("Bellnote 反馈包")
        appendLine()
        appendLine("diagnostics.txt  版本 / 机型 / 权限 / 设置 / 消息记录 / 崩溃（人看的）")
        appendLine("crash.txt        最近一次崩溃的结构化堆栈（异常类名 + at 帧，不含异常消息）")
        appendLine()
        appendLine("包里不含图片、不含待办标题与正文；API Key 与 ntfy 主题只报「有没有配、多少位」，内容不记录。")
        appendLine("URL 只保留协议与主机；崩溃堆栈和日志里的密钥、主题已抹除。")
        appendLine("这个包默认只给可信接收方，不要直接贴到公开 Issue。")
        if (includeVerbose) {
            appendLine("verbose.log      本次手动勾选包含的详细日志（含消息标题/正文/URL），仅本机保留 24 小时")
        }
        appendLine("问题描述请直接写在消息里，配合这份日志就够了。")
    }

    private fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    private fun yesNo(on: Boolean): String = if (on) "开" else "关"

    private fun at(time: Long): String =
        if (time <= 0L) "从未" else Diagnostics.stamp(time)
}
