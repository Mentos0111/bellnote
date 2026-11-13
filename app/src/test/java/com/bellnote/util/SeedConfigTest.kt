package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 礼包预置值的判断逻辑（纯函数，不碰 Android）。 */
class SeedConfigTest {

    private val seed = SeedConfig.Seed("sakura", "bn-friend-abc", "sk-demo")

    private fun current(
        theme: String = "teal",
        topic: String = "",
        apiKey: String = "",
        seeded: Boolean = false,
        onboardingDone: Boolean = false
    ) = SeedConfig.Current(theme, topic, apiKey, seeded, onboardingDone)

    @Test
    fun `普通安装包里没有预置值就什么都不做`() {
        assertNull(SeedConfig.plan(SeedConfig.Seed("", "", ""), current()))
    }

    @Test
    fun `第一次打开会写入全部三项`() {
        val plan = SeedConfig.plan(seed, current())
        assertEquals("sakura", plan?.theme)
        assertEquals("bn-friend-abc", plan?.topic)
        assertEquals("sk-demo", plan?.apiKey)
    }

    @Test
    fun `写过一次就不再写`() {
        assertNull(SeedConfig.plan(seed, current(seeded = true)))
    }

    @Test
    fun `用户已经在用这台手机时不动他的设置`() {
        assertNull(SeedConfig.plan(seed, current(onboardingDone = true)))
    }

    @Test
    fun `用户自己填过的主题和 Key 不覆盖`() {
        val plan = SeedConfig.plan(seed, current(topic = "my-topic", apiKey = "my-key"))
        assertEquals("sakura", plan?.theme)
        assertEquals("", plan?.topic)
        assertEquals("", plan?.apiKey)
    }

    @Test
    fun `只预置主题也能用`() {
        val plan = SeedConfig.plan(SeedConfig.Seed("sakura", "", ""), current())
        assertEquals("sakura", plan?.theme)
        assertEquals("", plan?.topic)
    }
}
