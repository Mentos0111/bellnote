package com.bellnote.ai

import com.bellnote.parser.MiniJson
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云端模型探测：用户填服务地址 + Key，这里去读 `{base}/models`，
 * 判断每个模型「能不能看图」——不能看图的允许保存，但要标出来。
 *
 * 为什么要判断识图能力：这套接口是 OpenAI 兼容的，同一个 Key 下往往既有
 * 纯文本模型（deepseek-chat、qwen-plus…）也有视觉模型（qwen-vl、gpt-4o…）。
 * 选错的话截图发过去也没用，只会在识别时报一句莫名的错。
 */
object CloudModels {

    data class Model(val id: String, val vision: Boolean, val reason: String)

    data class Result(
        val ok: Boolean,
        val models: List<Model> = emptyList(),
        val error: String? = null
    )

    /** 读模型列表。网络操作，必须在后台线程调用。 */
    fun list(baseUrl: String, apiKey: String): Result {
        if (apiKey.isBlank()) return Result(false, error = "还没填 API Key")
        val url = modelsEndpoint(baseUrl)
        var connection: HttpURLConnection? = null
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
            connection = conn
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val hint = when (code) {
                    401, 403 -> "Key 不对或没有权限"
                    404 -> "这个地址没有 /models 接口"
                    else -> body.take(200)
                }
                return Result(false, error = "读不到模型列表（$code）：$hint")
            }
            val ids = parseIds(body)
            if (ids.isEmpty()) {
                return Result(false, error = "列表是空的，可能这个服务不支持 /models，直接手填模型名吧")
            }
            Result(true, ids.map { inspect(it) }.sortedByDescending { it.vision })
        } catch (e: Exception) {
            Result(false, error = "连不上：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        } finally {
            connection?.disconnect()
        }
    }

    internal fun modelsEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/').ifBlank { VisionExtractor.DEFAULT_BASE_URL }
        return if (base.endsWith("/models")) base else "$base/models"
    }

    /** 从 {"data":[{"id":"..."},...]} 里抠出模型名；MiniJson 解析不了就正则兜底。 */
    internal fun parseIds(body: String): List<String> {
        val parsed = try {
            val node = MiniJson.parse(body)
            val data = if (node is MiniJson.Fields) node.fields["data"] else node
            (data as? MiniJson.Items)?.items?.mapNotNull { item ->
                ((item as? MiniJson.Fields)?.fields?.get("id") as? MiniJson.Text)?.text
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val ids = parsed.filter { it.isNotBlank() && it.length < 80 }.distinct()
        return ids.ifEmpty { regexIds(body) }
    }

    private fun regexIds(body: String): List<String> =
        Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").findAll(body).map { it.groupValues[1] }.distinct().toList()

    /**
     * 判断一个模型名能不能看图。没有官方能力字段，只能按名字认：
     * 先命中视觉关键词算「能」，再命中纯文本关键词算「不能」，都不中就「不确定」，
     * 不确定的按「能」放出去，但提示一句。
     */
    internal fun inspect(id: String): Model {
        val name = id.lowercase()
        val visionWords = listOf(
            "vl", "vision", "vlm", "-v-", "v-", "4o", "omni", "ocr", "llava", "pixtral",
            "internvl", "minicpm-v", "glm-4v", "gpt-4.1", "gemini", "claude", "qvq"
        )
        val textWords = listOf(
            "embedding", "embed", "tts", "whisper", "rerank", "moderation",
            "deepseek-chat", "deepseek-reasoner", "deepseek-coder",
            "gpt-3.5", "text-", "davinci"
        )
        val vision = visionWords.firstOrNull { name.contains(it) }
        val textOnly = textWords.firstOrNull { name.contains(it) }
        return when {
            vision != null -> Model(id, true, "看得到图（命中「$vision」）")
            textOnly != null -> Model(id, false, "像是纯文本模型，看不清截图")
            else -> Model(id, true, "没在识别清单里，按「应该能看图」处理，报错时再换")
        }
    }
}
