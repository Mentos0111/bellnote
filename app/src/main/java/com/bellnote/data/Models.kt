package com.bellnote.data

/** 待写入日历的一条待办。 */
data class TodoSpec(
    val title: String,
    val startMillis: Long,
    val durationMinutes: Int = 60,
    val note: String? = null,
    val repeat: RepeatRule = RepeatRule.NONE
) {
    val endMillis: Long get() = startMillis + durationMinutes * 60_000L
}

/** 从日历读回来的一条待办。 */
data class TodoItem(
    val id: Long,
    val title: String,
    val startMillis: Long,
    val durationMinutes: Int,
    val note: String?,
    val repeat: RepeatRule,
    val calendarId: Long
) {
    /** 转回可写入的形状，对账「补齐 / 修正」时用。 */
    fun toSpec(): TodoSpec = TodoSpec(
        title = title,
        startMillis = startMillis,
        durationMinutes = durationMinutes,
        note = note,
        repeat = repeat
    )
}
