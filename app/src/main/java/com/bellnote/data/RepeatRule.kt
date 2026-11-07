package com.bellnote.data

/** 重复规则，直接对应 RRULE 字符串。 */
enum class RepeatRule(val label: String, val rrule: String?) {
    NONE("不重复", null),
    DAILY("每天", "FREQ=DAILY"),
    WEEKLY("每周", "FREQ=WEEKLY"),
    MONTHLY("每月", "FREQ=MONTHLY"),
    YEARLY("每年", "FREQ=YEARLY");

    companion object {
        fun labels(): List<String> = values().map { it.label }

        fun fromLabel(label: String): RepeatRule =
            values().firstOrNull { it.label == label } ?: NONE

        fun fromIndex(index: Int): RepeatRule =
            values().getOrNull(index) ?: NONE

        /** 兼容 none / daily / 每周 / 每天 / RRULE 原文等多种写法。 */
        fun fromRaw(raw: String?): RepeatRule {
            val r = raw?.trim()?.lowercase() ?: return NONE
            if (r.isEmpty()) return NONE
            if (r.startsWith("freq=")) {
                return when {
                    r.contains("daily") -> DAILY
                    r.contains("weekly") -> WEEKLY
                    r.contains("monthly") -> MONTHLY
                    r.contains("yearly") -> YEARLY
                    else -> NONE
                }
            }
            return when {
                r in setOf("none", "no", "null", "不重复", "无", "单次", "一次", "once", "-") -> NONE
                r.contains("daily") || r.contains("day") || r.contains("每天") || r.contains("每日") || r == "d" -> DAILY
                r.contains("weekly") || r.contains("week") || r.contains("每周") || r.contains("每星期") || r.contains("周") -> WEEKLY
                r.contains("monthly") || r.contains("month") || r.contains("每月") || r.contains("月") -> MONTHLY
                r.contains("yearly") || r.contains("year") || r.contains("每年") || r.contains("年") -> YEARLY
                else -> NONE
            }
        }
    }
}
