package com.bellnote.ai

import com.bellnote.data.Prefs
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 识别方式的调度表：这台机器上「按什么顺序试」只由这里决定。
 * 重点保两条：自动模式断网/无 Key 不试云端；装了本地模型时自动模式会用上它。
 */
class EnginePlanTest {

    private fun chain(mode: String, key: Boolean = true, online: Boolean = true, llm: Boolean = false) =
        EnginePlan.chain(EnginePlan.Input(mode = mode, hasKey = key, online = online, llmReady = llm))

    @Test
    fun `自动 有网有Key 没模型：云端再本地`() {
        assertEquals(
            listOf(EnginePlan.Engine.CLOUD, EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_AUTO)
        )
    }

    @Test
    fun `自动 有网有Key 有模型：云端 模型 本地`() {
        assertEquals(
            listOf(EnginePlan.Engine.CLOUD, EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_AUTO, llm = true)
        )
    }

    @Test
    fun `自动 断网但有模型：跳过云端，先用模型`() {
        assertEquals(
            listOf(EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_AUTO, online = false, llm = true)
        )
    }

    @Test
    fun `自动 没Key：不调云端`() {
        assertEquals(
            listOf(EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_AUTO, key = false)
        )
    }

    @Test
    fun `自动 断网又没模型：只剩本地规则`() {
        assertEquals(
            listOf(EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_AUTO, online = false)
        )
    }

    @Test
    fun `只用本地：只有规则`() {
        assertEquals(listOf(EnginePlan.Engine.LOCAL), chain(Prefs.ENGINE_LOCAL, llm = true))
    }

    @Test
    fun `只用云端：只有云端，失败不降级`() {
        assertEquals(listOf(EnginePlan.Engine.CLOUD), chain(Prefs.ENGINE_CLOUD, online = false))
    }

    @Test
    fun `本地模型：模型没出结果也退回规则`() {
        assertEquals(
            listOf(EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(Prefs.ENGINE_LLM, llm = false)
        )
    }

    @Test
    fun `没选过按自动算`() {
        assertEquals(
            listOf(EnginePlan.Engine.CLOUD, EnginePlan.Engine.LOCAL),
            chain("", llm = false)
        )
    }
}
