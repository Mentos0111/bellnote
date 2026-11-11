package com.bellnote.data

/**
 * 同一条待办（标题 + 开始时间都相同）重新推过来时，内容是不是变了。
 *
 * 用在哪：ntfy 重发 / 补查重放 / 老师改了备注又发一遍。
 * 没变就别再动日历（避免重复写、避免把用户手改的地方刷掉）；
 * 变了就 updateEvent 覆盖 —— 以前是一律当重复跳过，导致「改了备注手机上还是旧的」。
 */
object SpecDiff {

    fun differs(item: TodoItem, spec: TodoSpec): Boolean {
        if (item.title.trim() != spec.title.trim()) return true
        if (item.durationMinutes != spec.durationMinutes) return true
        if (item.repeat != spec.repeat) return true
        return normalize(item.note) != normalize(spec.note)
    }

    private fun normalize(note: String?): String =
        note.orEmpty().replace("\r\n", "\n").trim()
}
