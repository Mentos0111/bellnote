package com.bellnote.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Canary 回归：用**明显是假的**密钥/主题/URL/正文，证明 zip 里每个文本附件
 * 在落盘前确实被脱敏，且标题/正文这类自由文本根本不存在于包里。
 *
 * 断言只检查「假值有没有消失」，不复述任何真实凭据或消息内容。
 */
class DiagnosticsCanaryTest {

    private val fakeKey = "sk-CANARY-0000-fake-key-1111"
    private val fakeTopic = "todo-canary-ffff0000"
    private val fakeUrl =
        "https://ntfy.example.com/$fakeTopic/json?poll=1&since=42&auth=CANARY-TOKEN"
    private val fakeTitle = "伪标题-CANARY-不要外传"
    private val fakeBody = "伪正文-CANARY-会议纪要-不得外传"

    private fun report(
        crash: String? = null,
        messages: List<Diagnostics.Message> = emptyList()
    ) = Diagnostics.Report(
        generatedAt = 1_760_000_000_000L,
        appName = "Bellnote",
        versionName = "3.21",
        versionCode = 32100,
        packageName = "com.bellnote",
        device = "TEST-DEVICE · fake · arm64-v8a",
        android = "Android 15（SDK 35）· test",
        locale = "zh-CN",
        timezone = "Asia/Shanghai",
        messages = messages,
        crash = crash,
        secrets = listOf(fakeKey, fakeTopic)
    )

    private fun texts(): Map<String, String> = Diagnostics.bundleTexts(
        report(
            crash = "java.io.IOException: 请求 $fakeUrl 失败 $fakeBody\n" +
                "\tat com.bellnote.service.NtfyFetcher.fetch(NtfyFetcher.kt:123)\n" +
                "Caused by: java.net.UnknownHostException: ntfy.example.com $fakeTitle",
            messages = listOf(
                Diagnostics.Message(1_760_000_000_000L, "收到", "补查", "没写进日历")
            )
        )
    ).toMap()

    private fun assertClean(name: String, text: String) {
        assertFalse("$name 泄露了伪 Key", text.contains(fakeKey))
        assertFalse("$name 泄露了伪主题", text.contains(fakeTopic))
        assertFalse("$name 泄露了伪标题", text.contains(fakeTitle))
        assertFalse("$name 泄露了伪正文", text.contains(fakeBody))
        assertFalse("$name 泄露了查询参数", text.contains("since=42"))
        assertFalse("$name 泄露了查询参数", text.contains("auth=CANARY-TOKEN"))
    }

    @Test
    fun `zip 文本附件只有主文件和结构化崩溃`() {
        val map = texts()
        assertTrue(map.containsKey("diagnostics.txt"))
        assertTrue(map.containsKey("crash.txt"))
        // logcat 已按最小收集原则去掉：日志可能夹带正文
        assertFalse(map.containsKey("logcat.txt"))
        // 详细日志默认不进包，只有反馈页显式勾选才会加（见 FeedbackBundle.build）
        assertFalse(map.containsKey("verbose.log"))
    }

    @Test
    fun `每个文本附件都不含伪 Key 主题标题正文与参数`() {
        texts().forEach { (name, text) -> assertClean(name, text) }
    }

    @Test
    fun `crash 附件保留堆栈结构但丢弃异常消息`() {
        val crash = texts()["crash.txt"].orEmpty()
        assertTrue(crash.contains("java.io.IOException"))
        assertTrue(crash.contains("at com.bellnote.service.NtfyFetcher.fetch"))
        assertTrue(crash.contains("Caused by:"))
        assertFalse(crash.contains("failed"))
    }

    @Test
    fun `diagnostics 主文件同样只含结构化消息`() {
        val main = texts()["diagnostics.txt"].orEmpty()
        assertClean("diagnostics.txt", main)
        assertTrue(main.contains("没写进日历"))
        assertTrue(main.contains("不收集 logcat"))
    }

    @Test
    fun `没有 crash 时不产生 crash 附件`() {
        val map = Diagnostics.bundleTexts(report()).toMap()
        assertTrue(map.containsKey("diagnostics.txt"))
        assertFalse(map.containsKey("crash.txt"))
    }

    @Test
    fun `勾选详细日志时才包含 verbose_log`() {
        val report = report(
            crash = "java.io.IOException: $fakeBody\n"
        )
        val verboseLine = "2026-11-15 10:00:00 [收] title=$fakeTitle | text=$fakeBody"

        // 不勾：任何情况下都不带 verbose.log
        val off = FeedbackBundle.zipEntries(report, includeVerbose = false, verboseText = verboseLine).toMap()
        assertFalse(off.containsKey("verbose.log"))
        assertClean("diagnostics.txt", off.getValue("diagnostics.txt"))
        assertClean("crash.txt", off.getValue("crash.txt"))

        // 勾选且文件存在：必须带 verbose.log
        val on = FeedbackBundle.zipEntries(report, includeVerbose = true, verboseText = verboseLine).toMap()
        assertTrue(on.containsKey("verbose.log"))
        assertEquals(verboseLine, on["verbose.log"])
        // 即便勾选，diagnostics.txt / crash.txt 仍不得出现伪标题/正文
        assertClean("diagnostics.txt", on.getValue("diagnostics.txt"))
        assertClean("crash.txt", on.getValue("crash.txt"))
    }

    @Test
    fun `勾选但详细日志为空或空白时不产生 verbose_log`() {
        val report = report()
        assertFalse(
            FeedbackBundle.zipEntries(report, includeVerbose = true, verboseText = null).toMap()
                .containsKey("verbose.log")
        )
        assertFalse(
            FeedbackBundle.zipEntries(report, includeVerbose = true, verboseText = "   ").toMap()
                .containsKey("verbose.log")
        )
    }

    @Test
    fun `即便不在 secrets 里 模式串与 URL 也会被抹掉`() {
        val fake = Diagnostics.redact("GET https://x.example.com/todo-abcdef123456/json?since=9")
        assertFalse(fake.contains("todo-abcdef123456"))
        assertFalse(fake.contains("since=9"))
        assertTrue(fake.contains("路径与参数已省略"))
    }
}
