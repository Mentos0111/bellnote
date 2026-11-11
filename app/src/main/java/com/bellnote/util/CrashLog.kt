package com.bellnote.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自捕获：不引第三方 SDK，就把最后一次崩溃写到应用私有目录，设置页能看。
 * realme 上后台被杀是不留痕迹的，至少「崩了」和「被杀了」要能分开。
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val FILE_NAME = "crash_last.txt"
    private const val MAX_CHARS = 20_000

    /** 装到 Application.onCreate 里，把默认处理器包一层。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(app, thread, error)
            } catch (e: Exception) {
                Log.w(TAG, "写崩溃日志失败", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText().ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "读崩溃日志失败", e)
            null
        }
    }

    fun clear(context: Context) {
        try {
            File(context.applicationContext.filesDir, FILE_NAME).delete()
        } catch (e: Exception) {
            Log.w(TAG, "清崩溃日志失败", e)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter()
        error.printStackTrace(PrintWriter(stack))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val entry = "==== $time  线程 ${thread.name} ====\n$stack\n"
        val file = File(context.filesDir, FILE_NAME)
        val old = if (file.exists()) file.readText() else ""
        val merged = (entry + old).take(MAX_CHARS)
        file.writeText(merged)
    }
}
