package com.bellnote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态灯判定与体检序列化（纯逻辑，不碰 Android）。 */
class HealthCheckTest {

    private fun item(status: HealthCheck.Status, label: String = "自启动") =
        HealthCheck.Item("id_$label", label, status, "详情", HealthCheck.Action.AUTOSTART)

    @Test
    fun `最严重的一项决定指示灯`() {
        val items = listOf(
            item(HealthCheck.Status.OK, "日历"),
            item(HealthCheck.Status.WARN, "侧边栏"),
            item(HealthCheck.Status.BAD, "自启动"),
            item(HealthCheck.Status.UNKNOWN, "自启动2")
        )
        assertEquals("自启动", HealthCheck.indicator(items)?.label)
        assertEquals(HealthCheck.Status.BAD, HealthCheck.indicator(items)?.status)
    }

    @Test
    fun `全部正常时指示灯是 OK`() {
        val items = listOf(item(HealthCheck.Status.OK, "日历"), item(HealthCheck.Status.OK, "监听"))
        assertEquals(HealthCheck.Status.OK, HealthCheck.indicator(items)?.status)
    }

    @Test
    fun `空列表没有指示灯`() {
        assertNull(HealthCheck.indicator(emptyList()))
    }

    @Test
    fun `待处理项只算 WARN 及以上`() {
        val items = listOf(
            item(HealthCheck.Status.OK, "日历"),
            item(HealthCheck.Status.UNKNOWN, "自启动"),
            item(HealthCheck.Status.WARN, "补查"),
            item(HealthCheck.Status.BAD, "通知权限")
        )
        assertEquals(2, HealthCheck.pending(items).size)
        assertEquals("2 项需要看一下", HealthCheck.summary(items))
    }

    @Test
    fun `全绿时汇总文案`() {
        val items = listOf(item(HealthCheck.Status.OK, "日历"))
        assertTrue(HealthCheck.pending(items).isEmpty())
        assertEquals("一切正常", HealthCheck.summary(items))
    }

    @Test
    fun `序列化和反序列化能还原状态与动作`() {
        val items = listOf(
            HealthCheck.Item("battery", "后台", HealthCheck.Status.BAD, "没加白名单", HealthCheck.Action.BATTERY),
            HealthCheck.Item("catchup", "补查", HealthCheck.Status.OK, "没有新消息", HealthCheck.Action.SETTINGS)
        )
        val raw = com.bellnote.service.HealthInspector.serialize(items)
        val restored = com.bellnote.service.HealthInspector.deserialize(raw)
        assertEquals(items.size, restored.size)
        assertEquals(items[0].status, restored[0].status)
        assertEquals(items[0].action, restored[0].action)
        assertEquals(items[1].label, restored[1].label)
        assertEquals(items[1].detail, restored[1].detail)
    }

    @Test
    fun `空缓存反序列化成空列表`() {
        assertTrue(com.bellnote.service.HealthInspector.deserialize("").isEmpty())
    }
}
