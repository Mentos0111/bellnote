package com.bellnote.net

import android.content.Context
import com.bellnote.parser.MiniJson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 版本检查：只认 Gitee 上那个固定地址的 version.json。
 *
 * 为什么是固定地址：GitHub 国内拉不动；Gitee 的 `latest` 发行版每次发版整份替换，
 * 地址永远不变，所以客户端里不用跟着版本号改代码。
 *
 * 隐私：这是纯 GET 的公开文件，不带任何设备信息、不带 API Key，只带一个
 * `User-Agent: Bellnote/<版本>`，方便以后看日志知道谁在拉。失败一律静默。
 */
object UpdateChecker {

    /** 发版时由 scripts/gitee_sync.py --latest 一起挂上去。 */
    const val VERSION_JSON_URL =
        "https://gitee.com/Mentos0111/bellnote/releases/download/latest/version.json"

    private const val TIMEOUT_MS = 8_000

    data class Info(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val downloadUrl: String,
    )

    /** 当前装的是哪个版本。 */
    fun currentVersionCode(context: Context): Int = runCatching {
        val pm = context.packageManager
        val pi = pm.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        pi.versionCode
    }.getOrDefault(0)

    fun currentVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** 拉 version.json。必须在后台线程调用；任何异常都返回 null。 */
    fun fetch(context: Context): Info? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Bellnote/" + currentVersionName(context))
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parse(text)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** version.json 是扁平结构，用现成的 MiniJson.flatScalars 就够。 */
    fun parse(raw: String): Info? {
        val fields = MiniJson.flatScalars(MiniJson.parse(raw))
        val code = fields["versionCode"]?.toIntOrNull() ?: return null
        val name = fields["versionName"].orEmpty().ifBlank { return null }
        return Info(
            versionName = name,
            versionCode = code,
            notes = fields["notes"].orEmpty(),
            downloadUrl = fields["downloadUrl"].orEmpty(),
        )
    }

    /**
     * 包里内嵌的 CHANGELOG（构建时从仓库根复制进 assets），用来「更新完弹更新日志」。
     * 读不到就返回空串，不抛。
     */
    fun bundledChangelog(context: Context): String = runCatching {
        context.assets.open("CHANGELOG.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")

    /** 从 CHANGELOG 里切出某一版的小节正文（去掉标题行）。 */
    fun sectionOf(changelog: String, version: String): String {
        if (changelog.isBlank() || version.isBlank()) return ""
        val lines = changelog.lines()
        val head = "## [$version]"
        var start = -1
        for (i in lines.indices) {
            if (lines[i].startsWith(head)) {
                start = i + 1
                break
            }
        }
        if (start < 0) return ""
        val out = StringBuilder()
        for (i in start until lines.size) {
            val line = lines[i]
            if (line.startsWith("## [")) break
            out.append(line).append('\n')
        }
        return out.toString().trim()
    }

    /**
     * 把 CHANGELOG 的 Markdown 压成手机弹窗里能看的样子：
     * 去掉 `###`/`##`/`**` 这些标记，列表符号换成「·」，空行收成一个。
     * 放这里而不是 UI 层：纯字符串逻辑，能直接写单测。
     */
    fun prettify(markdown: String): String {
        val out = StringBuilder()
        for (raw in markdown.lines()) {
            var line = raw.trim()
            if (line.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                continue
            }
            if (line.startsWith("###") || line.startsWith("##")) {
                line = line.trimStart('#').trim()
            }
            line = line.replace("**", "").replace("`", "")
            if (line.startsWith("- ")) line = "· " + line.removePrefix("- ")
            out.append(line).append('\n')
        }
        return out.toString().trim()
    }
}

