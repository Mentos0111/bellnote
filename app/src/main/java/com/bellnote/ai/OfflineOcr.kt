package com.bellnote.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

/**
 * 离线 OCR：ML Kit 中文识别，模型随包内置，不联网、不需要 GMS。
 *
 * 只负责「图 → 文字」，怎么从文字里抽待办是 [com.bellnote.parser] 的事。
 */
object OfflineOcr {

    private const val TAG = "OfflineOcr"

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 识别一张图。回调一定在主线程（ML Kit 的 Task 默认回到主线程）。
     * 失败时 [onDone] 收到以 "OCR 失败" 开头的字符串，调用方据此判断。
     */
    fun recognize(bitmap: Bitmap, onDone: (String) -> Unit) {
        val started = System.currentTimeMillis()
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    Log.i(TAG, "识别完成，${result.text.length} 字，耗时 ${System.currentTimeMillis() - started}ms")
                    onDone(result.text)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "识别失败", e)
                    onDone("OCR 失败：${e.javaClass.simpleName} ${e.message.orEmpty()}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "调用异常", e)
            onDone("OCR 失败：${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }
}
