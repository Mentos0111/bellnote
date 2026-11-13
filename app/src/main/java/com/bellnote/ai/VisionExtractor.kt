package com.bellnote.ai

import com.bellnote.data.DEFAULT_DURATION_MINUTES
import com.bellnote.data.PARSED_DEFAULT_DURATION_MINUTES
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
        /** 第一条（保持旧调用方兼容） */
        val spec: TodoSpec?,
        val error: String? = null,
        val raw: String = "",
        /** 截图里识别出的全部待办，可能不止一条 */
        val specs: List<TodoSpec> = emptyList()
    )

    private data class Response(val code: Int, val body: String)

    /** 默认服务地址；用户也可以填别的 OpenAI 兼容地址（Qwen、硅基、自建网关都行）。 */
    const val DEFAULT_BASE_URL = "https://api.deepseek.com"

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
            你负责把聊天 / 通知 / 课表截图整理成日历待办。只输出一个 **JSON 数组**，不要解释，不要 markdown 代码块。

            现在是 $stamp（$weekday）。所有相对说法（今天 / 明天 / 后天 / 周五 / 下周）都以此为准算成具体日期。

            怎么拆条：
            · 截图里**每件不同的事**各算一条，即使只有一件事也要用数组包起来：[{...}]
            · 同一件事的不同要求（例如「上课」和「带错题本」）不要拆开，放进同一条的 note
            · 只有一件明确要做的事时，数组里就只有一个对象

            每个对象怎么填：
            · title：**简略的待办标题**，不超过 16 个汉字，写成「动词 + 对象」。
              只保留最核心的那件事，去掉时间、地点、人名、客套话、语气词、标点。
              例：
                「收同学A的课堂笔记照片，看两色标记和错题有没有做」→「检查同学A笔记」
                「明天下午两点半到四点上化学课，讲勒夏特列原理」→「化学课：勒夏特列」
                「记得周五之前把物理卷子的错题本补完，要拍照发我」→「补物理错题本」
              · 标题里不要带日期、时间、星期、括号说明；这些信息放 note。
            · time："yyyy-MM-dd HH:mm"，这条待办该做的时间，**必须晚于上面的「现在」**。
              · 有明确截止时间（「截止 11-29 22:00」「周五之前交」）→ time 直接用这个截止时间，
                给了具体时刻就用那个时刻，不要自己往前挪。
              · 给的是周期 / 区间（「11-16 至 11-29」）→ 用开始那天的 09:00，结束日期写进 note。
              · 只写到「11 月中旬 / 月底」这种粗粒度 → 用该旬第一天 09:00。
              · 完全看不出日期 → 用 24 小时之后。
              · 截图里已经写明了日期数字（如 11-12、11月12日），**照抄那一年的月日**，只需补全年份；
                不要把它换算成星期再倒推（换算最容易差一天甚至差一年）。
              · 绝对不要输出早于「现在」的时间；算出来是过去，就说明你算错了，重算。
            · duration：整数分钟，尽量从截图推断：
              「2 小时 / 两小时」→120，「半小时」→30，「90 分钟」→90，「一节课」→45；
              如果给的是时间段（如 14:30-16:00），算两端差值；
              完全没有任何时长线索时才用 15（默认 15 分钟），不要默认成 60。
              只有「这件事要持续一阵」的明确线索（如培训周期、报名到截止）才把 duration 写大。
            · repeat：none / daily / weekly / monthly / yearly，看不出来用 none。
            · note：**这条标题以外、属于同一件事的有用信息**，短句、每行一条、最多 5 行。
              包括具体日期时间、地点、要带的东西、截止时间、对方强调的约束。
              不要重复 title，不要写「明天」这种相对说法，也不要写别的条目已经单独成条的内容。

            示例输出（两件事）：
            [
              {"title":"化学课：勒夏特列","time":"2026-11-09 14:30","duration":90,"repeat":"none","note":"11-09 14:30-16:00 上课\\n带上次的错题本\\n提前把错题拍照发家长"},
              {"title":"补物理错题本","time":"2026-11-13 20:00","duration":60,"repeat":"none","note":"周五前完成\\n完成后拍照发家长"}
            ]
        """.trimIndent()
    }

    internal const val USER_PROMPT =
        "这是聊天 / 通知 / 课表截图。把截图里**每件不同的事**各整理成一条日历待办" +
            "（可能只有一件，也可能有好几件，比如「某天上课」和「某天前交作业」）；" +
            "每一条都要能直接从截图里找到依据，不要自己编。严格按系统提示的字段规则输出 JSON 数组。"

    /** 标题上限：超过就截断，多出来的挪进备注，避免日历标题撑满一行。 */
    private const val MAX_TITLE_LENGTH = 20

    /**
     * 输出上限。800 tokens 时「一张截图多条待办 + 每条带备注」很容易被截断，
     * 截断后 JSON 不完整、整次识别直接失败，所以调到 2000。
     */
    internal const val MAX_TOKENS = 2000

    /** 网络抖动 / 5xx 重试一次，避免用户白等两分钟。 */
    private const val MAX_ATTEMPTS = 2

    fun extract(
        apiKey: String,
        model: String,
        jpegBase64: String,
        now: Long = System.currentTimeMillis(),
        baseUrl: String = DEFAULT_BASE_URL
    ): Outcome {
        if (apiKey.isBlank()) return Outcome(null, "还没配置云端 API Key")

        var usedModel = model
        var response = postWithRetry(apiKey, buildPayload(model, jpegBase64, now), baseUrl)
        var note = ""

        // 模型名被平台拒绝时（400 + supported API model names），自动换一个平台支持的模型重试一次
        if (response.code == 400 && response.body.contains("supported API model", ignoreCase = true)) {
            val supported = supportedModels(response.body)
            val alternative = pickAlternative(supported, model)
            if (alternative != null) {
                usedModel = alternative
                val retry = postWithRetry(apiKey, buildPayload(alternative, jpegBase64, now), baseUrl)
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
        android.util.Log.i("VisionExtractor", "模型返回：${content.take(600)}")
        if (!clean.contains("\"title\"") && !clean.contains("标题")) {
            return Outcome(null, "模型没有返回 JSON：${content.take(200)}", content)
        }
        val specs = parseSpecs(clean, now)
        if (specs.isEmpty()) {
            return Outcome(null, "模型返回的 JSON 里没有能用的待办：${content.take(200)}", content, emptyList())
        }
        return Outcome(
            spec = specs.first(),
            error = if (note.isBlank()) null else note.trim(),
            raw = content,
            specs = specs
        )
    }

    private val TITLE_KEYS = setOf("title", "标题", "主题", "题目", "事件", "待办", "事项", "name", "subject")
    private val TIME_KEYS =
        setOf("time", "时间", "开始", "开始时间", "日期", "date", "start", "when", "datetime")

    /** 模型可能给 `[{...},{...}]` 数组（我们要求的），也可能退回单个对象，两种都认。 */
    internal fun parseSpecs(clean: String, now: Long): List<TodoSpec> {
        val node = MiniJson.parse(clean)
        return when (node) {
            is MiniJson.Items -> node.items.mapNotNull { specFrom(it, now) }
            is MiniJson.Fields -> listOfNotNull(specFrom(node, now))
            else -> emptyList()
        }
    }

    private fun specFrom(value: MiniJson.Value, now: Long): TodoSpec? {
        val fields = MiniJson.flatScalars(value)
            .mapKeys { it.key.trim().lowercase(java.util.Locale.CHINA) }
        if (fields.isEmpty()) return null
        if (!fields.keys.any { it in TITLE_KEYS || it in TIME_KEYS }) return null
        return repair(tidy(TodoParser.fromFields(fields, now).spec), now)
    }

    /**
     * 模型最常犯的两类错，这里兜住：
     * ① 年份算错（把今年写成去年，或凭空编一个年份）→ 如果差一年后变成将来时间，就补一年；
     * ② 给了过去的时间 → 顺延到 24 小时后，并在备注里标一句，让你一眼看出「这条不太确定」。
     */
    internal fun repair(spec: TodoSpec, now: Long): TodoSpec {
        var start = spec.startMillis
        var note = spec.note
        val hour = 60 * 60 * 1000L
        val day = 24 * hour
        val year = 365L * day

        // 刚过去不久（一小时内到两个月内）→ 多半是日期读错了，放到明天更合理
        val past = now - start
        if (past in hour..(60 * day)) {
            start = now + day
            note = appendNote(note, "（读出来的时间已经过了，先放到明天，点进去改）")
        } else if (start < now - hour) {
            // 差得更远：先试「补一年」，补完是将来就说明模型少算了一年
            val plusYear = start + year
            if (plusYear > now) {
                start = plusYear
                note = appendNote(note, "（年份按今年修正）")
            } else {
                start = now + day
                note = appendNote(note, "（时间算不准，先放到明天，点进去改）")
            }
        }
        // 远期离谱（比如写成了三年后）→ 拉回一个月内，避免日历里出现怪事件
        if (start > now + 2 * year) {
            start = now + day
            note = appendNote(note, "（时间明显不合理，已先放到明天）")
        }
        return spec.copy(startMillis = start, note = note)
    }

    private fun appendNote(note: String?, extra: String): String =
        if (note.isNullOrBlank()) extra else "$note\n$extra"

    /**
     * 模型偶尔还是会给很长的标题或离谱的时长，这里做一层兜底：
     * 标题超长就截断、多出来的挪进备注；时长不在合理区间就按 60 分钟。
     */
    fun tidy(spec: TodoSpec): TodoSpec {
        val rawTitle = cleanTitle(spec.title)
        var title = rawTitle
        var note = normalizeNote(spec.note)

        if (title.length > MAX_TITLE_LENGTH) {
            val overflow = title.substring(MAX_TITLE_LENGTH).trim()
            title = title.take(MAX_TITLE_LENGTH - 1).trimEnd() + "…"
            note = if (note.isNullOrBlank()) overflow else "$overflow\n$note"
        }
        if (title.isBlank()) title = "截图待办"

        val duration = spec.durationMinutes.takeIf { it in DEFAULT_DURATION_MINUTES..1440 } ?: DEFAULT_DURATION_MINUTES
        return spec.copy(
            title = title,
            durationMinutes = duration,
            note = note
        )
    }

    /** 标题里混进日期/时间/「标题：」这类前缀时清掉——日历列表里只看得到前十个字。 */
    internal fun cleanTitle(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("^(标题|待办|事项|事件)\\s*[:：]\\s*"), "")
        // 开头的日期 / 时间片段，例如「11-12 20:30 」「11月12日 」
        t = t.replace(
            Regex("^(\\d{1,2}[-/月]\\d{1,2}日?|[今明后]天|周[一二三四五六日])(\\s*\\d{1,2}[:：]\\d{2})?\\s*"),
            ""
        )
        t = t.trim().trimEnd('。', '，', ',', '.', '；', ';', '：', ':')
        return t.ifBlank { raw.trim() }
    }

    /** 模型有时把换行写成字面 `\n`（反斜杠 + n），统一还原成真正的换行。 */
    private fun normalizeNote(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .trim()
            .ifBlank { null }
    }

    private fun post(apiKey: String, payload: String, baseUrl: String): Response {
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(chatEndpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
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

    /** 网络断 / 超时 / 5xx 自动重试一次；4xx（Key 错、参数错）不重试，重试也没用。 */
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

    /** 只接受 OpenAI 兼容写法：{base}/chat/completions。 */
    internal fun chatEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE_URL }
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
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

    internal fun buildPayload(model: String, jpegBase64: String, now: Long): String {
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
            put("max_tokens", MAX_TOKENS)
            put("messages", messages)
        }.toString()
    }

    /**
     * 去掉 ```json ... ``` 这类包装，只留 JSON 本体。
     * 注意要区分数组和对象：模型返回 `[{...},{...}]` 时必须保留外层方括号，
     * 否则会被切成 `{...},{...}` 这种非法 JSON，只解析出第一条。
     */
    fun stripFences(text: String): String {
        val arrayStart = text.indexOf('[')
        val objectStart = text.indexOf('{')
        val useArray = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)
        if (useArray) {
            val arrayEnd = text.lastIndexOf(']')
            if (arrayEnd > arrayStart) return text.substring(arrayStart, arrayEnd + 1)
        }
        if (objectStart >= 0) {
            val objectEnd = text.lastIndexOf('}')
            if (objectEnd > objectStart) return text.substring(objectStart, objectEnd + 1)
        }
        return text.trim()
    }
}
