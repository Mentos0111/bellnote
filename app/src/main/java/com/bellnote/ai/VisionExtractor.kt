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

    /**
     * 系统提示。**必须把「现在是什么时候」告诉模型**，否则它会把「明天」瞎算成随便一年 —
     * 之前的版本就这样把待办写成了 2025 年。
     */
    fun systemPrompt(now: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val ldt = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val stamp = ldt.format(
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
        )
        val weekday = when (ldt.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
        return """
            你负责把聊天 / 通知 / 课表截图整理成一条日历待办。只输出一个 JSON 对象，不要解释，不要 markdown 代码块。

            现在是 $stamp（$weekday）。所有相对说法（今天 / 明天 / 后天 / 周五 / 下周）都以此为准算成具体日期。

            各字段怎么填：
            · title：**简略的待办标题**，不超过 16 个汉字，写成「动词 + 对象」。
              只保留最核心的那件事，去掉时间、地点、人名、客套话、语气词、标点。
              例：
                「收学生的课堂笔记照片，看两色标记和错题有没有做」→「检查学生笔记」
                「明天下午两点半到四点上化学课，讲勒夏特列原理」→「化学课：勒夏特列」
                「记得周五之前把物理卷子的错题本补完，要拍照发我」→「补物理错题本」
            · time："yyyy-MM-dd HH:mm"，待办的开始时间，**必须晚于上面的「现在」**。
              截图里没写日期时，选今天或明天里最合理的一个；完全看不出时间才用 24 小时之后。
            · duration：整数分钟，尽量从截图推断：
              「2 小时 / 两小时」→120，「半小时」→30，「90 分钟」→90，「一节课」→45；
              如果给的是时间段（如 14:30-16:00），算两端差值；
              完全没有任何时长线索时才用 60。
            · repeat：none / daily / weekly / monthly / yearly，看不出来用 none。
            · note：**标题以外的所有有用信息**，短句、每行一条、最多 5 行。包括具体日期时间、
              地点、要带的东西、截止时间、对方强调的约束。不要重复 title，也不要写「明天」这种相对说法。

            示例输出：
            {"title":"检查学生笔记","time":"2026-11-08 20:00","duration":45,"repeat":"none","note":"看两色标记\n错题有没有做\n截止 11-08 20:00"}
        """.trimIndent()
    }

    private const val USER_PROMPT =
        "这是聊天 / 通知 / 课表截图。把里面最该做的那件事整理成一条日历待办，严格按系统提示的字段规则输出 JSON。"

    /** 标题上限：超过就截断，多出来的挪进备注，避免日历标题撑满一行。 */
    private const val MAX_TITLE_LENGTH = 20

    fun extract(
        apiKey: String,
        model: String,
        jpegBase64: String,
        now: Long = System.currentTimeMillis()
    ): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "还没配置 DeepSeek API Key")

        var usedModel = model
        var response = post(apiKey, buildPayload(model, jpegBase64, now))
        var note = ""

        // 模型名被平台拒绝时（400 + supported API model names），自动换一个平台支持的模型重试一次
        if (response.code == 400 && response.body.contains("supported API model", ignoreCase = true)) {
            val supported = supportedModels(response.body)
            val alternative = pickAlternative(supported, model)
            if (alternative != null) {
                usedModel = alternative
                val retry = post(apiKey, buildPayload(alternative, jpegBase64, now))
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
            tidy(parsed.spec),
            if (note.isBlank()) null else note.trim(),
            content
        )
    }

    /**
     * 模型偶尔还是会给很长的标题或离谱的时长，这里做一层兜底：
     * 标题超长就截断、多出来的挪进备注；时长不在合理区间就按 60 分钟。
     */
    fun tidy(spec: TodoSpec): TodoSpec {
        val rawTitle = spec.title.trim().replace(Regex("\\s+"), " ")
        var title = rawTitle
        var note = spec.note?.trim().orEmpty()

        if (title.length > MAX_TITLE_LENGTH) {
            val overflow = title.substring(MAX_TITLE_LENGTH).trim()
            title = title.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
            note = if (note.isBlank()) overflow else "$overflow\n$note"
        }
        if (title.isBlank()) title = "截图待办"

        val duration = spec.durationMinutes.takeIf { it in 5..1440 } ?: 60
        return spec.copy(
            title = title,
            durationMinutes = duration,
            note = note.ifBlank { null }
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

    private fun buildPayload(model: String, jpegBase64: String, now: Long): String {
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
                    put("content", systemPrompt(now))
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
