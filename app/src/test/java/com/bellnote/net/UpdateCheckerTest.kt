package com.bellnote.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 版本检查的纯逻辑：解析 version.json、切 CHANGELOG 小节、压成手机弹窗文本。 */
class UpdateCheckerTest {

    @Test
    fun `解析正常的 version json`() {
        val raw = """
            {
              "versionName": "3.21.6",
              "versionCode": 32106,
              "publishedAt": "2026-11-17",
              "downloadUrl": "https://gitee.com/x/y/releases/download/latest/bellnote-latest.apk",
              "sha256": "abc",
              "notes": "· 第一行\n· 第二行"
            }
        """.trimIndent()
        val info = UpdateChecker.parse(raw)
        requireNotNull(info)
        assertEquals("3.21.6", info.versionName)
        assertEquals(32106, info.versionCode)
        assertEquals("https://gitee.com/x/y/releases/download/latest/bellnote-latest.apk", info.downloadUrl)
        assertEquals("· 第一行\n· 第二行", info.notes)
    }

    @Test
    fun `缺版本号就当作不可用`() {
        assertNull(UpdateChecker.parse("""{"versionName":"3.21.6"}"""))
        assertNull(UpdateChecker.parse("""{"versionCode":32106}"""))
        assertNull(UpdateChecker.parse("不是 json"))
    }

    @Test
    fun `从 changelog 里切出指定版本的小节`() {
        val changelog = """
            # 更新记录

            ## [3.21.6] - 2026-11-17

            ### 新增

            - 检查更新

            ## [3.21.5] - 2026-11-17

            - 旧版本的东西
        """.trimIndent()
        val section = UpdateChecker.sectionOf(changelog, "3.21.6")
        assert(section.contains("检查更新"))
        assert(!section.contains("旧版本的东西"))
        assertEquals("", UpdateChecker.sectionOf(changelog, "9.9.9"))
    }

    @Test
    fun `压成弹窗文本时去掉 markdown 记号`() {
        val markdown = """
            ### 修复

            - **图标**没有显示：`补上位图`
        """.trimIndent()
        val text = UpdateChecker.prettify(markdown)
        assert(!text.contains("###"))
        assert(!text.contains("**"))
        assert(!text.contains("`"))
        assert(text.contains("图标"))
        assert(text.contains("· 图标没有显示"))
    }
}
