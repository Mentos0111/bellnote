package com.bellnote.ai

import com.bellnote.parser.MiniJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 把「用户手动输入的一句话 / 一段文字」交给云端模型，整理成日历待办。
 *
 * 和 [VisionExtractor] 的区别：不传图片，只传文字；解析和兜底完全复用
 * [VisionExtractor.parseSpecs] / [VisionExtractor.stripFences]，
 * 所以时间修复、标题截断、多条的规则和截图识别保持一致。
 *
 * 只依赖 OpenAI 兼容的 `/chat/completions`；地址校验沿用 [CloudEndpoint]，
 * 公网必须 HTTPS，局域网明文只放行本机 / 私有网段。
 */
object TextExtractor {

    private const val TAG = "TextExtractor"

    /** 一句话通常不需要 2000 token；留 1200 够多条待办和备注。 */
    internal const val MAX_TOKENS = 1200

    /** 网络抖动 / 5xx 重试一次。 */
    private const val MAX_ATTEMPTS = 2

    /** 用户输入太长时截断，避免把整篇文章发出去。 */
    private const val MAX_INPUT_CHARS = 2000

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    private data class Response(val code: Int, val body: String)

    /**
     * 文字 → 待办。必须在后台线程调用。
     *
     * @return [VisionExtractor.Outcome]；失败时 `specs` 为空，`error` 里带原因。
     */
    fun extract(
        apiKey: String,
        model: String,
        text: String,
        now: Long = System.currentTimeMillis(),
        baseUrl: String = VisionExtractor.DEFAULT_BASE_URL
    ): VisionExtractor.Outcome {
        if (apiKey.isBlank()) {
            return VisionExtractor.Outcome(null, "还没配置云端 API Key")
        }
        if (text.isBlank()) {
            return VisionExtractor.Outcome(null, "先在输入框里写一句要记的事")
        }
        val endpoint = CloudEndpoint.check(baseUrl)
        if (!endpoint.ok) {
            return VisionExtractor.Outcome(null, endpoint.reason ?: "云服务地址不合法")
        }

        val response = postWithRetry(apiKey, buildPayload(model, text, now), endpoint.normalized)
        if (response.code !in 200..299) {
            return VisionExtractor.Outcome(
                null,
                "接口返回 ${response.code}：${response.body.take(300)}",
                response.body
            )
        }
        return parseResponse(response.body, now)
    }

    /**
     * 从 OpenAI 兼容响应里取出 `choices[0].message.content`，再按截图识别的规则解析。
     * 纯函数，单测直接喂 JSON。
     */
    internal fun parseResponse(body: String, now: Long): VisionExtractor.Outcome {
        val root = MiniJson.parse(body) as? MiniJson.Fields
            ?: return VisionExtractor.Outcome(null, "接口返回不是 JSON：${body.take(180)}", body)
        val choices = root.fields["choices"] as? MiniJson.Items
        val firstChoice = choices?.items?.firstOrNull() as? MiniJson.Fields
        val message = firstChoice?.fields?.get("message") as? MiniJson.Fields
        val content = (message?.fields?.get("content") as? MiniJson.Text)?.text.orEmpty()

        val clean = VisionExtractor.stripFences(content)
        if (!clean.contains("\"title\"") && !clean.contains("标题")) {
            return VisionExtractor.Outcome(null, "模型没有返回待办 JSON：${content.take(160)}", content)
        }
        val specs = VisionExtractor.parseSpecs(clean, now)
        if (specs.isEmpty()) {
            return VisionExtractor.Outcome(null, "模型返回的 JSON 里没有能用的待办：${content.take(160)}", content)
        }
        return VisionExtractor.Outcome(
            spec = specs.first(),
            error = null,
            raw = content,
            specs = specs
        )
    }

    /**
     * 系统提示。把「现在几点」告诉模型，否则「明天 / 周五 / 晚上」会被算成瞎猜的日期。
     * 输出必须是数组，和截图识别保持同一份解析器。
     */
    internal fun systemPrompt(now: Long): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val stamp = at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
        val weekday = when (at.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
        val tomorrow = at.toLocalDate().plusDays(1)
        val exampleTime = tomorrow.atTime(9, 0)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
        val exampleTime2 = tomorrow.atTime(18, 0)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))

        return """
            你负责把用户手动输入的一句话或一段文字整理成日历待办。只输出一个 JSON 数组，不要解释，不要 markdown 代码块。

            现在是 $stamp（$weekday）。所有相对说法（今天 / 明天 / 后天 / 周五 / 下周 / 晚上）都以此为准算成具体日期。

            怎么拆条：
            · 每件不同的事各算一条，即使只有一件事也要用数组包起来：[{...}]
            · 同一件事的不同要求（例如「上课」和「带错题本」）不要拆开，放进同一条的 note
            · 只有一件明确要做的事时，数组里就只有一个对象

            每个对象怎么填：
            · title：不超过 16 个汉字，写成「动词 + 对象」。只保留最核心的那件事，去掉时间、地点、人名、客套话和语气词。
            · time："yyyy-MM-dd HH:mm"，必须晚于上面的「现在」。
              · 有明确截止时间（「截止 11-29 22:00」「周五之前交」）→ 直接用这个截止时间。
              · 只给日期没给时刻 → 用当天 09:00。
              · 完全看不出日期 → 用 24 小时之后。
              · 绝不要输出早于「现在」的时间；算出来是过去就说明你算错了，重算。
            · duration：整数分钟，尽量从话里推断。「两小时 / 2 小时」→120，「半小时」→30，「一节课」→45；完全没线索才用 15，不要默认成 60。
            · repeat：none / daily / weekly / monthly / yearly，看不出来用 none。
            · note：标题以外、属于同一件事的有用信息，短句、每行一条、最多 5 行。包括具体日期、地点、要带的东西、截止时间、对方强调的约束；不要重复 title，不要写「明天」这种相对说法。

            示例输出（两件事）：
            [
              {"title":"交实验报告","time":"$exampleTime","duration":15,"repeat":"none","note":"带实验记录本"},
              {"title":"买电池","time":"$exampleTime2","duration":15,"repeat":"none","note":"5 号电池两节"}
            ]
        """.trimIndent()
    }

    /** 只把用户文字放进 user 消息，系统规则放 system 消息。 */
    internal fun buildPayload(model: String, text: String, now: Long): String {
        val userText = text.trim().take(MAX_INPUT_CHARS)
        val messages = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt(now))
                }
            )
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "用户输入：\n$userText\n\n请按系统提示输出 JSON 数组。")
                }
            )
        }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("max_tokens", MAX_TOKENS)
            put("messages", messages)
        }.toString()
    }

    private fun postWithRetry(apiKey: String, payload: String, baseUrl: String): Response {
        var response = post(apiKey, payload, baseUrl)
        var attempt = 1
        while (attempt < MAX_ATTEMPTS && (response.code == -1 || response.code >= 500)) {
            Thread.sleep(1_000L * attempt)
            response = post(apiKey, payload, baseUrl)
            attempt++
        }
        return response
    }

    private fun post(apiKey: String, payload: String, baseUrl: String): Response {
        // 最后一道闸：地址不合法就不开连接
        val endpoint = CloudEndpoint.check(baseUrl)
        if (!endpoint.ok) {
            return Response(VisionExtractor.INVALID_URL_CODE, endpoint.reason ?: "云服务地址不合法")
        }
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(VisionExtractor.chatEndpoint(endpoint.normalized)).openConnection()
                as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
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
}
