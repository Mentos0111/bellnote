package com.bellnote.service

import android.content.Context
import android.util.Log
import com.bellnote.data.MessageLog
import com.bellnote.data.Prefs
import com.bellnote.util.NtfyEndpoint
import com.bellnote.util.TimeFormat
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从手机这头往 ntfy 主题里发一条消息。
 *
 * 用途：设置页「发一条测试推送」—— 不用电脑、不用脚本，自己验一遍整条链路。
 * 用 ntfy 的公开 POST 接口，不需要登录（公共 topic 谁都能发，本来就是这么设计的）。
 */
object NtfySender {

    private const val TAG = "NtfySender"
    private const val TIMEOUT_MS = 10_000

    data class Result(val ok: Boolean, val detail: String)

    /** 测试消息的正文，用 App 自己的键值格式，10 分钟后开始。 */
    fun buildTestMessage(now: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        val start = now + 10 * 60_000L
        return buildString {
            append("标题：Bellnote 自测\n")
            append("时间：${formatter.format(Date(start))}\n")
            append("时长：5\n")
            append("备注：设置页点出来的测试推送，收到就说明通道是通的，可以删掉这条。")
        }
    }

    /** 同步发送（调用方自己放后台线程）。 */
    fun sendTest(context: Context, now: Long = System.currentTimeMillis()): Result {
        if (!Prefs.ntfyEnabled(context)) return Result(false, "ntfy 模式关着，先在设置里打开")
        val topic = Prefs.ntfyTopic(context)
        if (topic.isBlank()) return Result(false, "设置里还没填 ntfy 主题")
        val server = Prefs.ntfyServer(context)
        val endpoint = NtfyEndpoint.check(server)
        if (!endpoint.ok) return Result(false, endpoint.reason ?: "服务器地址不对")
        val message = buildTestMessage(now)
        val body = buildRequestBody(topic, "Bellnote 自测", message)
        var connection: HttpURLConnection? = null
        val result = try {
            connection = (URL("${endpoint.normalized}/").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) {
                Result(true, "已发出（HTTP $code），10 分钟后那条「Bellnote 自测」应该出现在日历里")
            } else {
                Log.w(TAG, "测试推送失败 $code $text")
                Result(false, "ntfy 返回 HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "测试推送异常", e)
            Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
        MessageLog.log(
            context.applicationContext,
            MessageLog.DIR_OUT,
            "测试",
            "Bellnote 自测",
            message,
            if (result.ok) MessageLog.RESULT_SENT else MessageLog.RESULT_FAILED,
            result.detail
        )
        return result
    }

    /** 纯函数，方便单测：ntfy 的 JSON POST 体。 */
    fun buildRequestBody(topic: String, title: String, message: String): String = buildString {
        append("{")
        append("\"topic\":").append(quote(topic)).append(",")
        append("\"title\":").append(quote(title)).append(",")
        append("\"message\":").append(quote(message))
        append("}")
    }

    private fun quote(raw: String): String {
        val builder = StringBuilder("\"")
        for (c in raw) {
            when (c) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> builder.append(c)
            }
        }
        return builder.append('"').toString()
    }
}
