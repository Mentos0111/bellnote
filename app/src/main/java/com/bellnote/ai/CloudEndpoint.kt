package com.bellnote.ai

import com.bellnote.util.NtfyEndpoint
import java.net.URI

/**
 * 云端 OpenAI 兼容接口的地址规矩（和 [NtfyEndpoint] 同一套私网判断）。
 *
 * 为什么必须有：用户可以把这个地址改成任意 OpenAI 兼容服务，其中包括
 * `http://example.com/v1` 这种公网明文地址 —— 那样 API Key 和截图都会裸奔。
 * 所以规定：
 * - 公网只能用 https；
 * - 只有本机 / 局域网（127.0.0.1、10.x、172.16-31.x、192.168.x、*.local…）才允许 http，
 *   并在界面上明确提示「明文传输」；
 * - 空串回退默认 https://api.deepseek.com；畸形地址、非 http(s) 协议都明确报错。
 *
 * 校验点有三处：设置页保存前、[CloudModels.list] 读模型列表前、[VisionExtractor] 发请求前。
 * 后两处是必须的 —— 光靠保存按钮拦不住「配置里存了个坏地址」这种历史数据。
 */
object CloudEndpoint {

    /** 默认地址，和 [VisionExtractor.DEFAULT_BASE_URL] 保持一致。 */
    const val DEFAULT = VisionExtractor.DEFAULT_BASE_URL

    /** 任意协议前缀，例如 ftp://、file://；命中就交给 check() 拒绝。 */
    private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    data class Check(
        val ok: Boolean,
        val normalized: String,
        val reason: String? = null,
        /** true 表示用的是局域网明文 http，界面要提示风险。 */
        val cleartext: Boolean = false
    )

    /** 把随手输入的东西规整成可用地址；空串回默认。 */
    fun normalize(raw: String): String {
        var text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return DEFAULT
        // 只写了协议头（"http://"）时 trimEnd 会削成 "http:"，别当成主机名补 https
        if (text == "http:" || text == "https:") return raw.trim()
        // 已经带了任意协议就原样保留，交给 check() 去拒绝非 http(s) 的（ftp://、file://…）
        if (SCHEME_PREFIX.containsMatchIn(text)) return text
        val host = hostOf(text)
        text = (if (NtfyEndpoint.isPrivateHost(host)) "http://" else "https://") + text
        return text
    }

    /** 检查一份输入能不能用；不合法时 [Check.reason] 是给用户看的一句话。 */
    fun check(raw: String): Check {
        val normalized = normalize(raw)
        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            return Check(false, normalized, "地址写得不像一个网址")
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Check(false, normalized, "只支持 http:// 或 https://")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return Check(false, normalized, "没认出服务器地址")
        }
        if (scheme == "http") {
            if (!NtfyEndpoint.isPrivateHost(host)) {
                return Check(
                    false,
                    normalized,
                    "公网地址必须用 https://；http:// 只允许本机或家里的局域网（127.0.0.1 / 192.168 / 10 / 172.16）"
                )
            }
            return Check(true, normalized, null, cleartext = true)
        }
        return Check(true, normalized, null)
    }

    /** 保存前调用：直接把结果写回输入框（补全协议、去掉末尾斜杠）。 */
    fun checkForSave(raw: String): Check = check(raw)

    private fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/')
        return when {
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
            else -> authority.substringBefore(':')
        }
    }
}
