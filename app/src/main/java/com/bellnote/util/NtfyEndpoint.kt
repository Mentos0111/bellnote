package com.bellnote.util

import java.net.URI

/**
 * ntfy 服务器地址的规整与放行规则。
 *
 * 为什么要有它：App 从 3.17 起允许填自建服务器（包括局域网里的 `http://192.168.x.x:8088`），
 * 而 Android 默认禁明文 HTTP。为了不把「全 App 允许明文」变成安全漏洞，这里统一规定：
 * 公网只能用 https，http 只放行本机/私有网段。设置页保存前和实际发请求前都会问它。
 */
object NtfyEndpoint {

    const val DEFAULT = "https://ntfy.sh"

    data class Check(val ok: Boolean, val normalized: String, val reason: String? = null)

    /** 把用户随手输入的东西规整成可用地址；空串回到默认的 ntfy.sh。 */
    fun normalize(raw: String): String {
        var text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return DEFAULT
        // 只写了协议头（"http://"）时，trimEnd 会把它削成 "http:"，别当成主机名补 https
        if (text == "http:" || text == "https:") return raw.trim()
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            val host = hostOf(text)
            text = (if (isPrivateHost(host)) "http://" else "https://") + text
        }
        return text
    }

    /**
     * 检查一份输入能不能用。不合法时 [Check.reason] 是给用户看的一句话。
     */
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
        if (scheme == "http" && !isPrivateHost(host)) {
            return Check(
                false,
                normalized,
                "http:// 只用于家里的局域网地址（192.168 / 10 / 172.16 开头）；公网服务器请用 https://"
            )
        }
        return Check(true, normalized, null)
    }

    /** 本机、局域网、链路本地地址。用来决定能不能走明文 http。 */
    fun isPrivateHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".local")) return true

        val parts = h.split('.')
        if (parts.size == 4) {
            val nums = parts.map { it.toIntOrNull() ?: return false }
            if (nums.any { it !in 0..255 }) return false
            val (a, b) = nums
            return a == 10 ||
                a == 127 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168) ||
                (a == 169 && b == 254)
        }

        // IPv6：回环、链路本地、ULA
        if (h == "::1") return true
        return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    }

    private fun hostOf(url: String): String {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/')
        return when {
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
            else -> authority.substringBefore(':')
        }
    }
}
