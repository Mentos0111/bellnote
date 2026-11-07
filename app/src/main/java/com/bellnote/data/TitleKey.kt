package com.bellnote.data

/**
 * 标题比对：全角/半角空格、多余空白、大小写都不该让同一条待办"变成两条"。
 * 删除指令、对账比对、写入查重统一走这里，避免各处规则不一致。
 */
object TitleKey {

    private val SPACES = Regex("[\\s\\u3000]+")

    fun normalize(raw: String): String = raw.trim().replace(SPACES, " ")

    /**
     * 先按"压缩空白后完全相同"比；不中再按"去掉所有空白后相同"比。
     * 「重复   自检」和「重复自检」算同一条，但「A B」和「AB」只在精确比对失败时才划等号。
     */
    fun same(first: String, second: String): Boolean {
        val a = normalize(first)
        val b = normalize(second)
        if (a.equals(b, ignoreCase = true)) return true
        return a.replace(" ", "").equals(b.replace(" ", ""), ignoreCase = true)
    }
}
