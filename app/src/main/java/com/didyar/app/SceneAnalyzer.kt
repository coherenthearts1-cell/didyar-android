package com.didyar.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs

object SceneAnalyzer {
    fun analyzeFirstFiveMinutes(
        context: Context,
        uri: Uri,
        onProgress: (Int) -> Unit
    ): List<ScenePoint> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val endMs = minOf(durationMs, 5 * 60 * 1000L)
            val intervalMs = 2_000L
            if (endMs <= 0L) return listOf(ScenePoint(1, 0L))

            val sceneTimes = mutableListOf(0L)
            var previousSignature: IntArray? = null
            var t = 0L
            val totalSamples = ((endMs / intervalMs) + 1).toInt().coerceAtLeast(1)
            var sampleIndex = 0

            while (t <= endMs) {
                val frame = retriever.getFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    val signature = frameSignature(frame)
                    previousSignature?.let { previous ->
                        val difference = meanDifference(previous, signature)
                        val farEnough = t - sceneTimes.last() >= 4_000L
                        if (difference >= 28.0 && farEnough) {
                            sceneTimes += t
                        }
                    }
                    previousSignature = signature
                    if (!frame.isRecycled) frame.recycle()
                }

                sampleIndex++
                onProgress(((sampleIndex * 100.0) / totalSamples).toInt().coerceIn(0, 100))
                t += intervalMs
            }

            sceneTimes.mapIndexed { index, time ->
                ScenePoint(index = index + 1, timeMs = time)
            }
        } finally {
            retriever.release()
        }
    }

    private fun frameSignature(source: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(source, 24, 14, true)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val result = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            result[i] = (r * 30 + g * 59 + b * 11) / 100
        }
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        return result
    }

    private fun meanDifference(a: IntArray, b: IntArray): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0
        var sum = 0L
        for (i in 0 until size) {
            sum += abs(a[i] - b[i])
        }
        return sum.toDouble() / size
    }
}
