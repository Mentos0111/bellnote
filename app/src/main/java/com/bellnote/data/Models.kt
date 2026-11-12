package com.bellnote.data

/**
 * 存储/兜底用的最小长度（5 分钟）：日历事件读回来缺失时长、或手填低于这个值时用它。
 */
const val DEFAULT_DURATION_MINUTES = 5

/**
 * 文字/图片里只给了时间、没给持续多久时的默认时长。
 * 手动新建仍用 MANUAL_DEFAULT_DURATION_MINUTES。
 */
const val PARSED_DEFAULT_DURATION_MINUTES = 15

/**
 * 手动新建（人自己敲标题）时的默认时长。
 * 人手动建的通常是「一节课 / 一段事」，5 分钟不合理；推送和截图识别仍走 5 分钟。
 */
const val MANUAL_DEFAULT_DURATION_MINUTES = 60

/** 待写入日历的一条待办。 */
data class TodoSpec(
    val title: String,
    val startMillis: Long,
    val durationMinutes: Int = PARSED_DEFAULT_DURATION_MINUTES,
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
