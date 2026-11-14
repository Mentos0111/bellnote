package com.bellnote.data

/**
 * 一条 ntfy 主题配置。
 *
 * @param topic 真实主题名（服务器上那个，不能带空格）
 * @param label 只给自己看的备注名，比如「家里」「工作」；通知里不会出现
 * @param since 这条主题各自的上次补查时间（epoch 秒），多主题必须各存一个，
 *              共用一个游标会漏消息
 */
data class NtfyTopic(
    val topic: String,
    val label: String = "",
    val since: Long = 0L
) {
    /** 列表里显示成「家里 · todo-abc」这种。 */
    fun display(): String = if (label.isBlank()) topic else "$label · $topic"
}

/**
 * 多主题的存取编码。刻意用纯文本（一行一条、TAB 分隔），不用 JSON：
 * 这样能被 JVM 单测直接覆盖，不依赖 Android 的 org.json。
 *
 * 格式：`备注名[TAB]主题[TAB]since`；备注名里若出现 TAB/换行会被压成空格。
 */
object NtfyTopicCodec {

    const val MAX_TOPICS = 5
    private const val SEP = '\t'

    fun encode(list: List<NtfyTopic>): String =
        list.filter { it.topic.isNotBlank() }
            .take(MAX_TOPICS)
            .joinToString("\n") { t ->
                "${clean(t.label)}$SEP${t.topic.trim()}$SEP${t.since}"
            }

    fun decode(raw: String?): List<NtfyTopic> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n')
            .mapNotNull { line ->
                val parts = line.split(SEP)
                if (parts.size < 2) return@mapNotNull null
                val topic = parts[1].trim()
                if (topic.isBlank()) return@mapNotNull null
                NtfyTopic(
                    topic = topic,
                    label = clean(parts[0]),
                    since = parts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                )
            }
            .distinctBy { it.topic }
            .take(MAX_TOPICS)
    }

    private fun clean(s: String): String = s.replace('\t', ' ').replace('\n', ' ').trim()

    /** 加一条：同主题已存在就只更新备注名，不重复占位。 */
    fun upsert(list: List<NtfyTopic>, incoming: NtfyTopic): List<NtfyTopic> {
        val topic = incoming.topic.trim()
        if (topic.isBlank()) return list
        val existing = list.firstOrNull { it.topic == topic }
        val merged = if (existing != null) {
            list.map {
                if (it.topic == topic) it.copy(label = incoming.label.ifBlank { it.label }) else it
            }
        } else {
            list + NtfyTopic(topic, incoming.label, incoming.since)
        }
        return merged.take(MAX_TOPICS)
    }
}
