package com.bellnote.ai

/**
 * 「一句话记一条」的 AI 识别链路。
 *
 * 和截图链路的区别：这里没有 OCR，也没有本地规则兜底 ——
 * 本地规则由首页自己跑（[com.bellnote.parser.QuickParse]），
 * 这个类只负责决定「先问云端，还是先用本地模型」。
 *
 * - 云端要「开着 + 有 Key」
 * - 本地模型要「开着 + 文件已下载」
 * - 两个都不可用就返回空列表，由界面提示去设置
 */
object TextEnginePlan {

    enum class Engine { CLOUD, LLM }

    fun chain(
        cloudOn: Boolean,
        hasKey: Boolean,
        localOn: Boolean,
        llmReady: Boolean
    ): List<Engine> = buildList {
        if (cloudOn && hasKey) add(Engine.CLOUD)
        if (localOn && llmReady) add(Engine.LLM)
    }
}
