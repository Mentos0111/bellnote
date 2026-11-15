package com.bellnote.data

/**
 * 「没写进日历」的原因码。
 *
 * 只放**固定枚举**，不携带任何自由文本：消息正文、异常 message、URL 都不该进这里。
 * 消息记录页显示中文；诊断包写「CODE（中文）」。
 */
enum class ReasonCode(val code: String, val zh: String) {
    MISSING_TIME("MISSING_TIME", "缺少时间"),
    UNPARSABLE_TIME("UNPARSABLE_TIME", "时间解析不了"),
    DUPLICATE("DUPLICATE", "重复，未再写入"),
    IGNORED("IGNORED", "被忽略"),
    CALENDAR_PERMISSION_DENIED("CALENDAR_PERMISSION_DENIED", "缺少日历权限"),
    CALENDAR_WRITE_FAILED("CALENDAR_WRITE_FAILED", "写入日历失败"),
    NETWORK_ERROR("NETWORK_ERROR", "网络错误"),
    AUTH_ERROR("AUTH_ERROR", "认证失败"),
    PARSE_ERROR("PARSE_ERROR", "解析失败"),
    UNKNOWN("UNKNOWN", "未知原因");

    /** 展示用：`CODE（中文）`。 */
    fun display(): String = "$code（$zh）"

    companion object {
        fun fromCode(raw: String?): ReasonCode? =
            entries.firstOrNull { it.code == raw?.trim() }

        /**
         * 把历史/内部的说明归一到原因码。成功写入返回 null（不记录原因）。
         * 纯函数，单测直接覆盖。
         */
        fun classify(detail: String?, result: String? = null): ReasonCode? {
            val d = detail.orEmpty()
            val r = result.orEmpty()
            val success = r.contains("写进") || r.contains("更新") || r.contains("已进") || r.contains("sent")
            if (d.isBlank() && success) return null
            if (d.isBlank()) return ReasonCode.UNKNOWN
            return when {
                d.contains("缺少日历权限") || d.contains("日历权限") -> CALENDAR_PERMISSION_DENIED
                d.contains("没认出时间") || d.contains("缺时间") || d.contains("没写时间") -> MISSING_TIME
                d.contains("时间解析") || d.contains("时间看不懂") || d.contains("时间格式") -> UNPARSABLE_TIME
                d.contains("重复") || d.contains("已存在") -> DUPLICATE
                d.contains("写入失败") || d.contains("写失败") || d.contains("日历写入")
                    || d.contains("写入日历") || d.contains("写进日历失败") -> CALENDAR_WRITE_FAILED
                d.contains("网络") || d.contains("连不上") || d.contains("HTTP") || d.contains("超时") -> NETWORK_ERROR
                d.contains("Key") || d.contains("401") || d.contains("403") || d.contains("认证") -> AUTH_ERROR
                d.contains("解析") || d.contains("不是 JSON") || d.contains("格式") -> PARSE_ERROR
                d.contains("忽略") || d.contains("跳过") -> IGNORED
                else -> UNKNOWN
            }
        }

        /** 消息记录页用：只给中文，不带枚举码。 */
        fun labelOf(raw: String?): String {
            val code = fromCode(raw) ?: return raw?.trim().orEmpty()
            return code.zh
        }

        /** 消息记录页/诊断包展示：已知码翻中文，历史自由文本原样返回（兼容旧记录）。 */
        fun display(raw: String?): String {
            val code = fromCode(raw) ?: return raw?.trim().orEmpty()
            return code.display()
        }
    }
}
