package com.bellnote.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 只干一件事：替「Bellnote 截屏记」这个侧边栏入口截一张当前屏幕。
 *
 * 为什么非要用无障碍：Android 11 起 `takeScreenshot()` 才能截到**别的 App**的界面，
 * 而这个 API 只有已授权的无障碍服务能调。MediaProjection 每次都要弹系统授权框，
 * 从侧边栏点一下就想出图是不现实的。
 * 本服务不读输入、不监听内容，只提供一个截屏能力（`canRetrieveWindowContent` 也要开着，
 * 否则部分 ROM 会拒绝 takeScreenshot）。
 */
class ScreenCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "截屏服务已连接")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "ScreenCapture"

        @Volatile
        private var instance: ScreenCaptureService? = null

        fun isConnected(): Boolean = instance != null

        /**
         * 开关是不是开着（读系统设置）。
         * 为什么要这个：App 进程刚被系统重启时，无障碍服务还没绑定回来，
         * 只看内存里的 instance 会误判成「没开」，把用户引去设置页白跑一趟。
         */
        fun isEnabledInSettings(context: android.content.Context): Boolean = try {
            val raw = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            raw.contains(context.packageName + "/" + ScreenCaptureService::class.java.name)
        } catch (e: Exception) {
            false
        }

        /** 等无障碍服务绑定好（进程刚起来时会有一小段空窗），最多等 [timeoutMs]。 */
        fun awaitConnection(timeoutMs: Long, onReady: (Boolean) -> Unit) {
            if (isConnected()) {
                onReady(true)
                return
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    if (isConnected()) {
                        onReady(true)
                    } else if (System.currentTimeMillis() >= deadline) {
                        onReady(false)
                    } else {
                        handler.postDelayed(this, 120)
                    }
                }
            }
            handler.post(poll)
        }

        /**
         * 截当前屏幕。Android 11 以下没有这个 API，回调 null。
         * 注意：回调在系统线程，调用方自己切主线程。
         */
        fun capture(callback: (Bitmap?) -> Unit) {
            val service = instance
            Log.i(TAG, "capture() 开始，service=${service != null} sdk=${Build.VERSION.SDK_INT}")
            if (service == null) {
                callback(null)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "系统版本低于 Android 11，没有 takeScreenshot()")
                callback(null)
                return
            }
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executor { runnable -> runnable.run() },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            Log.i(TAG, "截屏成功 ${screenshot.hardwareBuffer.width}x${screenshot.hardwareBuffer.height}")
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (e: Exception) {
                                Log.w(TAG, "转换截屏位图失败", e)
                                null
                            }
                            screenshot.hardwareBuffer.close()
                            callback(bitmap)
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.w(TAG, "截屏失败 errorCode=$errorCode")
                            callback(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "截屏异常", e)
                callback(null)
            }
        }
    }
}
