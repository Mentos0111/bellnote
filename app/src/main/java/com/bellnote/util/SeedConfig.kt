package com.bellnote.util

import android.content.Context
import com.bellnote.BuildConfig
import com.bellnote.data.Prefs
import com.bellnote.service.NtfyPollScheduler

/**
 * 「礼包」预置配置：把配色、ntfy 主题、（可选）API Key 编进 APK，首次启动一次写好。
 *
 * 边界（重要）：
 * - 普通构建 / CI 产出的包里这三个值都是空的，[plan] 返回 null，什么都不做；
 * - 只在「还没走完第一次引导」且没写过预置值时生效，已经在用的手机不会被改设置；
 * - 主题、ntfy 主题、Key 逐项判断：用户自己填过的值不覆盖；
 * - 预置的 Key 会被写进 SharedPreferences，而它本来就编在 APK 里 —— **只能私下发给朋友，
 *   不能公开分发**，要收回只能让对方装回普通版并换 Key。
 */
object SeedConfig {

    data class Seed(val theme: String, val topic: String, val apiKey: String) {
        val isEmpty: Boolean get() = theme.isBlank() && topic.isBlank() && apiKey.isBlank()
    }

    data class Current(
        val theme: String,
        val topic: String,
        val apiKey: String,
        val seeded: Boolean,
        val onboardingDone: Boolean
    )

    /** 包里编进去的值（普通包全空）。 */
    fun bundled(): Seed = Seed(
        theme = BuildConfig.SEED_THEME.trim(),
        topic = BuildConfig.SEED_NTFY_TOPIC.trim(),
        apiKey = decode(BuildConfig.SEED_API_KEY_B64.trim())
    )

    /**
     * 这次启动要写哪些值；返回 null 表示什么都不写。
     * 纯函数，单测覆盖「普通包 / 首次启动 / 已经用过的手机 / 用户自己填过」几种情况。
     */
    fun plan(seed: Seed, current: Current): Seed? {
        if (seed.isEmpty) return null
        if (current.seeded) return null
        // 已经走完引导 = 这台手机在用，别动它
        if (current.onboardingDone) return null
        val result = Seed(
            theme = seed.theme,
            topic = if (current.topic.isBlank()) seed.topic else "",
            apiKey = if (current.apiKey.isBlank()) seed.apiKey else ""
        )
        return if (result.isEmpty) null else result
    }

    /**
     * 启动时调用一次：先算要写什么，再写，并把「预置值已经用过」记下来，
     * 以后用户改了设置也不会被礼包值盖回去。
     */
    fun applyIfNeeded(context: Context) {
        val seed = bundled()
        if (seed.isEmpty) return
        val plan = plan(
            seed,
            Current(
                theme = Prefs.theme(context),
                topic = Prefs.ntfyTopic(context),
                apiKey = Prefs.apiKey(context),
                seeded = Prefs.seedApplied(context),
                onboardingDone = Prefs.onboardingDone(context)
            )
        )
        Prefs.setSeedApplied(context, true)
        if (plan == null) return
        if (plan.theme.isNotBlank()) {
            Prefs.setTheme(context, plan.theme)
            // 开机就切桌面图标会把正在启动的任务收掉，记下来等退到后台再换
            ThemeManager.scheduleLauncherIcon()
        }
        if (plan.topic.isNotBlank()) {
            Prefs.setNtfyTopic(context, plan.topic)
            Prefs.setNtfyEnabled(context, true)
            NtfyPollScheduler.reschedule(context)
        }
        if (plan.apiKey.isNotBlank()) Prefs.setApiKey(context, plan.apiKey)
    }

    private fun decode(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            String(java.util.Base64.getDecoder().decode(raw), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            try {
                String(java.util.Base64.getUrlDecoder().decode(raw), Charsets.UTF_8).trim()
            } catch (e2: Exception) {
                ""
            }
        }
    }
}
