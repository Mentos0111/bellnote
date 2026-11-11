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
         * 截当前屏幕。Android 11 以下没有这个 API，回调 null。
         * 注意：回调在系统线程，调用方自己切主线程。
         */
        fun capture(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null) {
                callback(null)
                return
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                callback(null)
                return
            }
            try {
                service.takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    java.util.concurrent.Executor { runnable -> runnable.run() },
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )?.copy(Bitmap.Config.ARGB_8888, false)
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
