package com.bellnote.data

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 进程内的「待办变了」通知。
 *
 * 为什么需要它：后台收待办是前台服务在轮询，写库那一刻没有任何东西告诉正在显示的首页，
 * 于是停在首页时列表一直不动，得切走再回来才刷新（2026-11-17 真机 bug）。
 *
 * 这里只做事件广播，不碰数据库、不轮询：写库方调 [notifyChanged]，
 * 界面在 onStart 注册、onStop 注销，收到就自己重新查一次。
 * 用 CopyOnWriteArrayList，允许写库线程（服务轮询线程）通知主线程上的监听方。
 */
object TodoEvents {

    /** 监听方直接写在主线程上，自己再切线程。 */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile
    private var changeCount: Long = 0L

    fun addListener(listener: (String) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 数据真的变了才调（新建 / 更新 / 改期 / 删除成功）。
     * @param source 谁改的，只用于排查，界面不关心。
     */
    fun notifyChanged(source: String) {
        changeCount++
        for (listener in listeners) {
            try {
                listener(source)
            } catch (e: Exception) {
                // 界面崩了也不能把写库的那一方带崩（服务线程里没有界面兜底）
            }
        }
    }

    /** 累计通知次数，单测和诊断用。 */
    fun changeCount(): Long = changeCount

    fun listenerCount(): Int = listeners.size

    /** 单测之间互不影响。 */
    fun resetForTest() {
        listeners.clear()
        changeCount = 0L
    }
}
