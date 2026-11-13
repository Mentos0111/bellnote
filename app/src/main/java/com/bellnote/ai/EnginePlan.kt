package com.bellnote.ai

/**
 * 识别链路：本地规则永远是地板，云端模型 / 本地模型是两张可开可关的「增强卡」。
 *
 * - 两个都开：云端 → 本地模型 → 本地规则（越准越优先，失败就退下一个）
 * - 只开云端：云端 → 本地规则
 * - 只开本地模型：本地模型 → 本地规则
 * - 都不开：只用本地规则
 *
 * 顺序只在这一个地方定义，导入页按顺序执行、拿到结果就停。
 */
object EnginePlan {

    enum class Engine { CLOUD, LLM, LOCAL }

    data class Input(
        val cloudOn: Boolean,
        val localOn: Boolean,
        val hasKey: Boolean,
        val online: Boolean,
        /** 本地模型下载好没有：没下好就算开了也走不了 */
        val llmReady: Boolean
    )

    fun chain(input: Input): List<Engine> = buildList {
        if (input.cloudOn && input.hasKey && input.online) add(Engine.CLOUD)
        if (input.localOn && input.llmReady) add(Engine.LLM)
        add(Engine.LOCAL)
    }
}
