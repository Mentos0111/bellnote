package com.bellnote.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/** 截图压缩：先按尺寸采样，再等比缩放到 maxSide，最后压成 JPEG / base64。 */
object ImageUtils {

    private const val TAG = "ImageUtils"

    fun toJpegBase64(context: Context, uri: Uri, maxSide: Int = 1280, quality: Int = 80): String? {
        return try {
            val bitmap = loadBitmap(context, uri, maxSide) ?: return null
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "图片转 base64 失败", e)
            null
        }
    }

    fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSide && bounds.outHeight / (sample * 2) >= maxSide) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null

            val longest = maxOf(decoded.width, decoded.height)
            if (longest <= maxSide) {
                decoded
            } else {
                val scale = maxSide.toFloat() / longest
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled !== decoded) decoded.recycle()
                scaled
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取图片失败", e)
            null
        }
    }
}
