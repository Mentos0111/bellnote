package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 识别链路的调度表。
 *
 * 规则：本地规则永远在最后兜底；云端要「开着 + 有 Key + 有网」才排进去；
 * 本地模型要「开着 + 已下载」才排进去。
 */
class EnginePlanTest {

    private fun chain(
        cloud: Boolean = false,
        local: Boolean = false,
        key: Boolean = true,
        online: Boolean = true,
        llmReady: Boolean = false
    ) = EnginePlan.chain(
        EnginePlan.Input(
            cloudOn = cloud,
            localOn = local,
            hasKey = key,
            online = online,
            llmReady = llmReady
        )
    )

    @Test
    fun `什么都不开：只剩本地规则`() {
        assertEquals(listOf(EnginePlan.Engine.LOCAL), chain())
    }

    @Test
    fun `只开云端：云端再规则`() {
        assertEquals(
            listOf(EnginePlan.Engine.CLOUD, EnginePlan.Engine.LOCAL),
            chain(cloud = true)
        )
    }

    @Test
    fun `只开本地模型：模型再规则`() {
        assertEquals(
            listOf(EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(local = true, llmReady = true)
        )
    }

    @Test
    fun `两个都开：云端 模型 规则`() {
        assertEquals(
            listOf(EnginePlan.Engine.CLOUD, EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(cloud = true, local = true, llmReady = true)
        )
    }

    @Test
    fun `云端没配 Key 就不排它`() {
        assertEquals(listOf(EnginePlan.Engine.LOCAL), chain(cloud = true, key = false))
    }

    @Test
    fun `断网跳过云端`() {
        assertEquals(
            listOf(EnginePlan.Engine.LOCAL),
            chain(cloud = true, online = false)
        )
    }

    @Test
    fun `断网但模型开着：先用模型`() {
        assertEquals(
            listOf(EnginePlan.Engine.LLM, EnginePlan.Engine.LOCAL),
            chain(cloud = true, local = true, online = false, llmReady = true)
        )
    }

    @Test
    fun `模型开了但没下载：走规则，不排队`() {
        assertEquals(listOf(EnginePlan.Engine.LOCAL), chain(local = true, llmReady = false))
    }
}
