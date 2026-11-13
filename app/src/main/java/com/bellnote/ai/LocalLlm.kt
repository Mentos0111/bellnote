package com.bellnote.ai

import android.content.Context
import android.util.Log
import com.bellnote.data.TodoSpec
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 离线大模型：在手机上跑 Gemma 3 1B（int4，MediaPipe LLM Inference），断网也能把屏幕文字
 * 理解成待办。
 *
 * 和 [com.bellnote.parser.ScreenTextParser] 的分工：
 * - 规则解析负责「格式规整」的行（明天下午三点 / 11月14日 20:30），快、结果可解释；
 * - 离线模型负责规则认不出的说法（「下周三之前」「周五晚自习带卷子」），慢一些但能理解。
 *
 * 模型文件不进 APK（约 690MB），跟着外部文件目录走：`files/models/gemma3-1b-it-q4.task`。
 * 没装模型时这里一律返回 null，调用方退回规则解析。
 */
object LocalLlm {

    private const val TAG = "LocalLlm"

    const val MODEL_DIR = "models"
    const val MODEL_NAME = "gemma3-1b-it-q4.task"

    /** 小于这个体积的肯定是没下完的残包，别拿去加载（加载会直接崩）。 */
    const val MIN_BYTES = 600L * 1024 * 1024

    /** 送进模型的屏幕文字上限：再多也只是浪费 token，还容易跑偏。 */
    private const val MAX_INPUT_CHARS = 1_200

    fun modelFile(context: Context): File =
        File(File(context.getExternalFilesDir(null), MODEL_DIR), MODEL_NAME)

    fun isReady(context: Context): Boolean {
        val file = modelFile(context)
        val ok = file.isFile && file.length() > MIN_BYTES
        Log.i(
            "LocalLlm",
            "模型检查 path=${file.absolutePath} exists=${file.exists()} file=${file.isFile} " +
                "readable=${file.canRead()} size=${file.length()} ok=$ok"
        )
        return ok
    }

    /** 装好模型占多大（给设置页显示用）。 */
    fun modelSize(context: Context): Long = modelFile(context).let { if (it.isFile) it.length() else 0L }

    @Volatile
    private var inference: LlmInference? = null

    /**
     * 加载（第一次要几秒，之后复用）。失败抛异常，调用方负责提示。
     * 必须在后台线程调用。
     */
    @Synchronized
    fun instance(context: Context): LlmInference {
        inference?.let { return it }
        val file = modelFile(context)
        require(file.isFile) { "离线模型还没下载" }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file.absolutePath)
            .setMaxTokens(1024)
            // 贪心解码：同一个输入每次结果一致，也不会因为采样跑偏输出空数组
            .setMaxTopK(1)
            .build()
        val created = LlmInference.createFromOptions(context.applicationContext, options)
        inference = created
        return created
    }

    /** 换模型 / 删模型后要把内存里的实例丢掉，否则还指着老文件。 */
    @Synchronized
    fun release() {
        runCatching { inference?.close() }
        inference = null
    }

    fun prompt(screenText: String, now: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val stamp = at.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA))
        val weekday = when (at.dayOfWeek.value) {
            1 -> "周一"; 2 -> "周二"; 3 -> "周三"; 4 -> "周四"
            5 -> "周五"; 6 -> "周六"; else -> "周日"
        }
        return buildString {
            appendLine("你是待办提取器。现在是 $stamp $weekday。")
            appendLine("把下面屏幕文字里要做的事抽成 JSON 数组，只输出 JSON，不要解释。")
            appendLine("每条形如 {\"title\":\"交实验报告\",\"time\":\"2026-11-14 09:00\",\"duration\":15}")
            appendLine("例子：")
            appendLine("屏幕文字：明天 9:00 交实验报告 / 周五晚自习带卷子")
            appendLine("输出：[{\"title\":\"交实验报告\",\"time\":\"2026-11-14 09:00\",\"duration\":15},{\"title\":\"带卷子\",\"time\":\"2026-11-14 19:00\",\"duration\":15}]")
            appendLine("规则：time 用 yyyy-MM-dd HH:mm 且必须晚于现在；认不出时间的条目不要输出；title 不超过 20 个字；至少输出一条。")
            appendLine("屏幕文字：")
            append(screenText.take(MAX_INPUT_CHARS))
            appendLine()
            append("输出：")
        }
    }

    /**
     * 屏幕文字 → 待办。必须在后台线程调用。
     *
     * @return 解析出的待办；模型没装 / 加载失败 / 输出不是 JSON 时 [VisionExtractor.Outcome.specs]
     *         为空，[VisionExtractor.Outcome.error] 里带原因。
     */
    fun extract(
        context: Context,
        screenText: String,
        now: Long = System.currentTimeMillis()
    ): VisionExtractor.Outcome {
        if (!isReady(context)) {
            return VisionExtractor.Outcome(null, "离线模型还没下载")
        }
        return try {
            val engine = instance(context)
            val raw = engine.generateResponse(prompt(screenText, now))
            // 小的 1B 模型按 token 生成，中文标题里常被塞进空格（「交 实验报告」），拼回去
            val specs = VisionExtractor.parseSpecs(VisionExtractor.stripFences(raw), now)
                .map { spec -> spec.copy(title = tidyTitle(spec.title)) }
            VisionExtractor.Outcome(
                spec = specs.firstOrNull(),
                error = if (specs.isEmpty()) "离线模型没解析出待办" else null,
                raw = raw,
                specs = specs
            )
        } catch (e: Exception) {
            Log.w(TAG, "离线推理失败", e)
            VisionExtractor.Outcome(null, "离线模型出错：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim())
        }
    }

    /** 去掉汉字之间被模型插进来的空格，别的空格（英文、数字之间）保留。 */
    internal fun tidyTitle(title: String): String =
        title.replace(Regex("(?<=\\p{IsHan})\\s+(?=\\p{IsHan})"), "").trim()

    /** 试跑：给设置页的「跑一行看看」用，顺便把耗时带回去。 */
    data class Probe(
        val millis: Long,
        val raw: String,
        val specs: List<TodoSpec>,
        val error: String?
    )

    fun probe(context: Context, text: String, now: Long = System.currentTimeMillis()): Probe {
        val started = System.currentTimeMillis()
        val outcome = extract(context, text, now)
        return Probe(
            millis = System.currentTimeMillis() - started,
            raw = outcome.raw,
            specs = outcome.specs,
            error = outcome.error
        )
    }
}
