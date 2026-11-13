package com.bellnote.ai

import android.content.Context
import com.bellnote.R
import com.bellnote.data.Prefs
import java.io.File

/**
 * 可选的离线模型。都来自 ModelScope 的 litert-community 镜像，MediaPipe 直接能加载。
 *
 * 为什么给三个：手机上跑模型永远是「体积 / 速度 / 质量」三选二 ——
 * - 270M：下得快、跑得快，只够应付短句；
 * - 0.5B（Qwen）：中文明显比 Gemma 小模型好，体积也可接受；
 * - 1B（Gemma）：理解力最强，但下载 657 MB、每次推理 5 秒左右。
 */
data class LocalModel(
    val id: String,
    val name: String,
    /** 卡片/状态里用的短名 */
    val short: String,
    val desc: String,
    val fileName: String,
    val url: String,
    val bytes: Long
) {
    val sizeMb: Long get() = bytes / 1024 / 1024
}

object LocalModels {

    const val DEFAULT_ID = "gemma-1b"

    private const val BASE =
        "https://www.modelscope.cn/models/litert-community"

    val all: List<LocalModel> = listOf(
        LocalModel(
            id = "gemma-270m",
            name = "Gemma 3 270M · 轻量",
            short = "270M",
            desc = "289 MB · 最快，只适合短句",
            fileName = "gemma3-270m-it-q8.task",
            url = "$BASE/Gemma-3-270M-IT/resolve/master/gemma3-270m-it-q8.task",
            bytes = 289_900_000L
        ),
        LocalModel(
            id = "qwen-0-5b",
            name = "Qwen2.5 0.5B · 中文",
            short = "Qwen 0.5B",
            desc = "521 MB · 中文比 Gemma 小模型好",
            fileName = "qwen2.5-0.5b-instruct-q8.task",
            url = "$BASE/Qwen2.5-0.5B-Instruct/resolve/master/" +
                "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            bytes = 546_600_000L
        ),
        LocalModel(
            id = "gemma-1b",
            name = "Gemma 3 1B · 标准",
            short = "Gemma 1B",
            desc = "657 MB · 理解力最好，推理约 5 秒",
            fileName = "gemma3-1b-it-q4.task",
            url = "$BASE/Gemma3-1B-IT/resolve/master/" +
                "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv1280.task",
            bytes = 689_297_398L
        )
    )

    fun byId(id: String): LocalModel = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT_ID }

    /** 现在选中的是哪个（没选过就是标准那个）。 */
    fun selected(context: Context): LocalModel = byId(Prefs.localModelId(context))

    fun file(context: Context, model: LocalModel): File =
        File(File(context.getExternalFilesDir(null), LocalLlm.MODEL_DIR), model.fileName)

    /** 小于这个体积的肯定是残包，别拿去加载（加载会直接崩）。 */
    fun isReady(context: Context, model: LocalModel): Boolean {
        val file = file(context, model)
        return file.isFile && file.length() > model.bytes * 9 / 10
    }
}
