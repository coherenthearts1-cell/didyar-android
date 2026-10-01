package com.didyar.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

object SceneFrameExtractor {
    fun extractBase64Jpegs(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long?
    ): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            val safeStart = startMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
            val safeEnd = (endMs ?: (safeStart + 4_000L))
                .coerceAtMost(durationMs)
                .coerceAtLeast(safeStart)

            val span = (safeEnd - safeStart).coerceAtLeast(500L)

            val sampleCount = when {
                span <= 20_000L -> 7
                span <= 60_000L -> 5
                else -> 4
            }

            val times = buildList {
                for (i in 1..sampleCount) {
                    val fraction = i.toDouble() / (sampleCount + 1).toDouble()
                    val time = safeStart + (span * fraction).toLong()
                    add(time.coerceIn(0L, durationMs))
                }
            }.distinct()

            times.mapNotNull { timeMs ->
                val frame = retriever.getFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: return@mapNotNull null

                try {
                    val resized = resizeForUpload(frame, 640)
                    try {
                        val bytes = ByteArrayOutputStream().use { out ->
                            resized.compress(Bitmap.CompressFormat.JPEG, 72, out)
                            out.toByteArray()
                        }
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } finally {
                        if (resized !== frame && !resized.isRecycled) {
                            resized.recycle()
                        }
                    }
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun resizeForUpload(source: Bitmap, maxSide: Int): Bitmap {
        val width = source.width
        val height = source.height
        val largest = maxOf(width, height)
        if (largest <= maxSide) return source

        val scale = maxSide.toFloat() / largest.toFloat()
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, true)
    }
}
