package com.bellnote.data

/**
 * 没说时长时用这个：日历能接受的最小长度（5 分钟）。
 * ntfy 推送、截图识别、手动新建都走这一个默认值，避免同一个 App 出现两套默认。
 */
const val DEFAULT_DURATION_MINUTES = 5

/** 待写入日历的一条待办。 */
data class TodoSpec(
    val title: String,
    val startMillis: Long,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
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
