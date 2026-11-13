package com.bellnote.ai

import com.bellnote.data.Prefs

/**
 * 「识别方式」的调度表：给定模式与当前条件，算出这次要按什么顺序试。
 *
 * 为什么要单独算：以前云端 / 本地模型 / 本地规则各写一套回退，改一处忘一处；
 * 现在顺序只在这一个地方定义，导入页按顺序执行、拿到结果就停。
 *
 * 顺序原则：越准的越前，失败才退到下一个；「自动」在没有网络或没有 Key 时直接跳过云端。
 */
object EnginePlan {

    enum class Engine { CLOUD, LLM, LOCAL }

    data class Input(
        /** [Prefs.ocrEngine] 的值；空串表示还没选过（按自动算）。 */
        val mode: String,
        val hasKey: Boolean,
        val online: Boolean,
        val llmReady: Boolean
    )

    fun chain(input: Input): List<Engine> = when (input.mode) {
        Prefs.ENGINE_CLOUD -> listOf(Engine.CLOUD)
        Prefs.ENGINE_LOCAL -> listOf(Engine.LOCAL)
        // 选了本地模型：模型没装/没出结果时退回规则，不让用户空手
        Prefs.ENGINE_LLM -> listOf(Engine.LLM, Engine.LOCAL)
        else -> buildList {
            if (input.online && input.hasKey) add(Engine.CLOUD)
            if (input.llmReady) add(Engine.LLM)
            add(Engine.LOCAL)
        }
    }
}
