package com.bellnote.ai

import com.bellnote.data.TodoSpec
import com.bellnote.parser.MiniJson
import com.bellnote.parser.TodoParser
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 调 DeepSeek 视觉模型，把截图变成一条待办（只输出 JSON）。 */
object VisionExtractor {

    data class Outcome(
        val spec: TodoSpec?,
        val error: String? = null,
        val raw: String = ""
    )

    private data class Response(val code: Int, val body: String)

    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"

    private val SYSTEM_PROMPT = """
        你是作业通知的信息抽取器。只输出一个 JSON 对象，不要解释，不要用 markdown 代码块。
        字段：title(字符串)、time("yyyy-MM-dd HH:mm")、duration(整数，分钟)、repeat(none|daily|weekly|monthly|yearly)、note(字符串)。
        截图里没有明确时间时，time 用今天之后最近一个合理时间（例如明天 19:00）；没有时长用 60；没有重复用 "none"。
    """.trimIndent()

    private const val USER_PROMPT = "这是一张聊天或通知截图，请识别出其中的作业待办，只输出 JSON。"

    fun extract(
        apiKey: String,
        model: String,
        jpegBase64: String,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "还没配置 DeepSeek API Key")

        var usedModel = model
        var response = post(apiKey, buildPayload(model, jpegBase64))
        var note = ""

        // 模型名被平台拒绝时（400 + supported API model names），自动换一个平台支持的模型重试一次
        if (response.code == 400 && response.body.contains("supported API model", ignoreCase = true)) {
            val supported = supportedModels(response.body)
            val alternative = pickAlternative(supported, model)
            if (alternative != null) {
                usedModel = alternative
                val retry = post(apiKey, buildPayload(alternative, jpegBase64))
                response = retry
                note = "（模型名 $model 不被支持，已自动改用 $alternative）"
            }
            if (response.code !in 200..299) {
                val hint = if (supported.isEmpty()) "" else "平台支持：${supported.joinToString(" / ")}"
                return Outcome(
                    null,
                    "模型名不被支持。$hint\n去设置页把「视觉模型」改成其中之一再试。" + note,
                    response.body
                )
            }
        }

        if (response.code !in 200..299) {
            return Outcome(null, "接口返回 ${response.code}：${response.body.take(400)}", response.body)
        }

        val root = MiniJson.parse(response.body) as? MiniJson.Fields
            ?: return Outcome(null, "接口返回不是 JSON：${response.body.take(200)}", response.body)
        val choices = root.fields["choices"] as? MiniJson.Items
        val firstChoice = choices?.items?.firstOrNull() as? MiniJson.Fields
        val message = firstChoice?.fields?.get("message") as? MiniJson.Fields
        val content = (message?.fields?.get("content") as? MiniJson.Text)?.text.orEmpty()

        val clean = stripFences(content)
        if (!clean.contains("\"title\"") && !clean.contains("标题")) {
            return Outcome(null, "模型没有返回 JSON：${content.take(200)}", content)
        }
        val parsed = TodoParser.parse(null, clean, now)
        return Outcome(
            parsed.spec,
            if (note.isBlank()) null else note.trim(),
            content
        )
    }

    private fun post(apiKey: String, payload: String): Response {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            connection = conn
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Response(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            Response(-1, "请求异常：${e.javaClass.simpleName} ${e.message.orEmpty()}")
        } finally {
            connection?.disconnect()
        }
    }

    /** 从 400 报错里抠出平台支持的模型名。 */
    fun supportedModels(errorBody: String): List<String> =
        Regex("[A-Za-z0-9][A-Za-z0-9._-]*").findAll(errorBody)
            .map { it.value }
            .filter { it.startsWith("deepseek", ignoreCase = true) }
            .distinct()
            .toList()

    private fun pickAlternative(supported: List<String>, current: String): String? {
        val candidates = supported.filter { !it.equals(current, ignoreCase = true) }
        return candidates.firstOrNull { it.contains("flash", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun buildPayload(model: String, jpegBase64: String): String {
        val message = JSONObject().apply {
            put("role", "user")
            put(
                "content", JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("type", "text")
                            put("text", USER_PROMPT)
                        }
                    )
                    put(
                        JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url", JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$jpegBase64")
                                }
                            )
                        }
                    )
                }
            )
        }
        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                }
            )
            put(message)
        }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("max_tokens", 800)
            put("messages", messages)
        }.toString()
    }

    /** 去掉 ```json ... ``` 这类包装，只留花括号里的内容。 */
    fun stripFences(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return text.trim()
    }
}
