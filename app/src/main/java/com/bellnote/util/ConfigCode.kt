package com.bellnote.util

import java.util.zip.CRC32

/**
 * 「配置码」：把「配色主题 + ntfy 主题」编成一串能直接发微信的文字，朋友粘一下就能导入。
 *
 * 为什么不用 JSON：这段逻辑要在单元测试里跑（没有 Android 的 org.json），
 * 而且配置只有两个字段，用控制字符分隔 + base64 更省事、也不怕主题名里带引号。
 *
 * 格式：`BN1.<4 位 CRC32 十六进制>.<base64url 内容>`，内容形如 `v=1\u0001t=sakura\u0001n=todo-abc`。
 * 带校验位是为了用户手抄/转发时少一个字能立刻看出来，而不是导入一半失败。
 *
 * **不含 API Key**：Key 不在这串码里，朋友导入后要自己填（或以后单独做 Key 的分享方式）。
 */
object ConfigCode {

    private const val PREFIX = "BN1"
    private const val VERSION = "1"
    private const val SEP = '\u0001'

    data class Payload(val theme: String, val topic: String)

    sealed class Decoded {
        data class Ok(val payload: Payload) : Decoded()
        data class Failed(val reason: String) : Decoded()
    }

    fun encode(theme: String, topic: String): String {
        val body = listOf(
            "v=$VERSION",
            "t=" + theme.replace(SEP, ' ').trim(),
            "n=" + topic.replace(SEP, ' ').trim()
        ).joinToString(SEP.toString())
        val bytes = body.toByteArray(Charsets.UTF_8)
        val crc = crcOf(bytes)
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        return "$PREFIX.$crc.$b64"
    }

    fun decode(raw: String): Decoded {
        val text = raw.replace(Regex("\\s+"), "")
        if (text.isEmpty()) return Decoded.Failed("先把配置码粘贴进来")
        if (!text.startsWith("$PREFIX.", ignoreCase = true)) {
            return Decoded.Failed("这不像 Bellnote 配置码：应该以 $PREFIX. 开头")
        }
        val parts = text.split('.')
        if (parts.size != 3) return Decoded.Failed("配置码格式不对：中间少了分隔符或复制不全")
        val checksum = parts[1].lowercase()
        val bytes = try {
            java.util.Base64.getUrlDecoder().decode(parts[2])
        } catch (e: Exception) {
            return Decoded.Failed("配置码里的内容读不出来，可能复制时少了一段")
        }
        if (crcOf(bytes) != checksum) {
            return Decoded.Failed("配置码校验没过：转发时可能少了字符，让对方重新复制一遍")
        }
        val fields = bytes.toString(Charsets.UTF_8).split(SEP)
        val map = fields.mapNotNull { field ->
            val at = field.indexOf('=')
            if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
        }.toMap()
        if (map["v"] != VERSION) return Decoded.Failed("这串配置码是更新版本 App 生成的，先升级 Bellnote")
        val theme = map["t"].orEmpty().trim()
        val topic = map["n"].orEmpty().trim()
        if (theme.isEmpty() && topic.isEmpty()) return Decoded.Failed("配置码里没有可导入的设置")
        if (theme.isEmpty()) return Decoded.Failed("配置码里没有配色，请对方重新生成")
        return Decoded.Ok(Payload(theme, topic))
    }

    private fun crcOf(bytes: ByteArray): String {
        val crc = CRC32()
        crc.update(bytes)
        return (crc.value and 0xFFFFFFFFL).toString(16).padStart(8, '0').takeLast(4)
    }
}
