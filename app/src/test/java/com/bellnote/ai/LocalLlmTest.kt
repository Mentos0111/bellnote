package com.bellnote.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 离线小模型按 token 生成，汉字之间常被插空格；这里只管「标题看起来正常」这一件事。
 */
class LocalLlmTest {

    @Test
    fun `汉字之间的空格会被去掉`() {
        assertEquals("交实验报告", LocalLlm.tidyTitle("交 实验报 告"))
        assertEquals("把第三章错题重做一遍", LocalLlm.tidyTitle("把第三章 错题重做一遍"))
    }

    @Test
    fun `英文和数字两侧的空格保留`() {
        assertEquals("DSH 复盘 9 份", LocalLlm.tidyTitle("DSH 复盘 9 份"))
        assertEquals("看 pH 换算", LocalLlm.tidyTitle("看 pH 换算"))
    }

    @Test
    fun `首尾空格清掉`() {
        assertEquals("化学课", LocalLlm.tidyTitle("  化学课  "))
    }
}
