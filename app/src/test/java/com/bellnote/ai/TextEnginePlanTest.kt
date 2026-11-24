package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** 文字识别的调度表：云端优先，本地模型其次；两个都不可用就返回空。 */
class TextEnginePlanTest {

    private fun chain(
        cloud: Boolean = false,
        key: Boolean = true,
        local: Boolean = false,
        llmReady: Boolean = true
    ) = TextEnginePlan.chain(
        cloudOn = cloud,
        hasKey = key,
        localOn = local,
        llmReady = llmReady
    )

    @Test
    fun `都不开：没有 AI 入口`() {
        assertEquals(emptyList<TextEnginePlan.Engine>(), chain())
    }

    @Test
    fun `只开云端：先云端`() {
        assertEquals(listOf(TextEnginePlan.Engine.CLOUD), chain(cloud = true))
    }

    @Test
    fun `云端没 Key 不排`() {
        assertEquals(emptyList<TextEnginePlan.Engine>(), chain(cloud = true, key = false))
    }

    @Test
    fun `只开本地模型：用本地`() {
        assertEquals(listOf(TextEnginePlan.Engine.LLM), chain(local = true))
    }

    @Test
    fun `本地模型没下载完不排`() {
        assertEquals(emptyList<TextEnginePlan.Engine>(), chain(local = true, llmReady = false))
    }

    @Test
    fun `两个都可用：云端在前，本地兜底`() {
        assertEquals(
            listOf(TextEnginePlan.Engine.CLOUD, TextEnginePlan.Engine.LLM),
            chain(cloud = true, local = true)
        )
    }
}
