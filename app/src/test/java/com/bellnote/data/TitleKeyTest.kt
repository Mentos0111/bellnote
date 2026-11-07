package com.bellnote.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleKeyTest {

    @Test
    fun `完全相同算同一条`() {
        assertTrue(TitleKey.same("数学作业", "数学作业"))
    }

    @Test
    fun `前后空白不影响`() {
        assertTrue(TitleKey.same("  数学作业  ", "数学作业"))
    }

    @Test
    fun `中间多空格也算同一条`() {
        assertTrue(TitleKey.same("重复   自检", "重复自检"))
    }

    @Test
    fun `全角空格也算`() {
        assertTrue(TitleKey.same("重复\u3000自检", "重复 自检"))
    }

    @Test
    fun `大小写不影响英文标题`() {
        assertTrue(TitleKey.same("Math Homework", "math homework"))
    }

    @Test
    fun `中文标题只有在精确比对失败时才忽略空格`() {
        assertTrue(TitleKey.same("A B", "AB"))
        assertFalse(TitleKey.same("数学作业", "语文作业"))
    }

    @Test
    fun `完全不同的标题不相等`() {
        assertFalse(TitleKey.same("化学复习", "物理复习"))
    }
}
