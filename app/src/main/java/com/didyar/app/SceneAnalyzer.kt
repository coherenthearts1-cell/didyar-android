package com.didyar.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs

object SceneAnalyzer {
    private const val SAMPLE_INTERVAL_MS = 2_000L
    private const val MIN_SCENE_GAP_MS = 8_000L

    private data class VisualSignature(
        val grayPixels: IntArray,
        val colorHistogram: DoubleArray
    )

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
            if (endMs <= 0L) return listOf(ScenePoint(1, 0L))

            val sampleTimes = mutableListOf<Long>()
            val signatures = mutableListOf<VisualSignature>()
            var t = 0L
            val totalSamples =
                ((endMs / SAMPLE_INTERVAL_MS) + 1).toInt().coerceAtLeast(1)
            var sampleIndex = 0

            while (t <= endMs) {
                val frame = retriever.getFrameAtTime(
                    t * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                if (frame != null) {
                    sampleTimes += t
                    signatures += frameSignature(frame)
                    if (!frame.isRecycled) frame.recycle()
                }

                sampleIndex++
                onProgress(
                    ((sampleIndex * 100.0) / totalSamples)
                        .toInt()
                        .coerceIn(0, 100)
                )
                t += SAMPLE_INTERVAL_MS
            }

            if (signatures.isEmpty()) {
                return listOf(ScenePoint(1, 0L))
            }

            val acceptedSampleIndices = mutableListOf(0)

            for (i in 1 until signatures.size) {
                val currentTime = sampleTimes[i]
                val lastAcceptedIndex = acceptedSampleIndices.last()
                if (currentTime - sampleTimes[lastAcceptedIndex] < MIN_SCENE_GAP_MS) {
                    continue
                }

                val before = signatures[i - 1]
                val current = signatures[i]

                val adjacentPixelJump =
                    meanDifference(before.grayPixels, current.grayPixels)
                val adjacentHistogramJump =
                    histogramDistance(before.colorHistogram, current.colorHistogram)

                val next1 = signatures.getOrNull(i + 1)
                val next2 = signatures.getOrNull(i + 2)

                val persistentAcrossNextFrames =
                    next1 != null &&
                        next2 != null &&
                        meanDifference(before.grayPixels, next1.grayPixels) >= 34.0 &&
                        meanDifference(before.grayPixels, next2.grayPixels) >= 32.0 &&
                        histogramDistance(before.colorHistogram, next1.colorHistogram) >= 0.18 &&
                        histogramDistance(before.colorHistogram, next2.colorHistogram) >= 0.16

                val looksLikeRealCut =
                    adjacentPixelJump >= 42.0 &&
                        adjacentHistogramJump >= 0.22 &&
                        persistentAcrossNextFrames

                if (!looksLikeRealCut) continue

                val lastAccepted = signatures[lastAcceptedIndex]
                val fromLastScenePixels =
                    meanDifference(lastAccepted.grayPixels, current.grayPixels)
                val fromLastSceneHistogram =
                    histogramDistance(
                        lastAccepted.colorHistogram,
                        current.colorHistogram
                    )

                val stillSameVisualSetup =
                    fromLastScenePixels < 34.0 &&
                        fromLastSceneHistogram < 0.18

                if (!stillSameVisualSetup) {
                    acceptedSampleIndices += i
                }
            }

            acceptedSampleIndices
                .map { sampleTimes[it] }
                .distinct()
                .sorted()
                .mapIndexed { index, time ->
                    ScenePoint(index = index + 1, timeMs = time)
                }
        } finally {
            retriever.release()
        }
    }

    private fun frameSignature(source: Bitmap): VisualSignature {
        val scaled = Bitmap.createScaledBitmap(source, 12, 8, true)
        val width = scaled.width
        val height = scaled.height
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(pixels.size)
        val histogram = DoubleArray(48)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF

            gray[i] = (r * 30 + g * 59 + b * 11) / 100

            histogram[r / 16] += 1.0
            histogram[16 + (g / 16)] += 1.0
            histogram[32 + (b / 16)] += 1.0
        }

        val pixelCount = pixels.size.coerceAtLeast(1).toDouble()
        for (channel in 0 until 3) {
            val offset = channel * 16
            for (bin in 0 until 16) {
                histogram[offset + bin] /= pixelCount
            }
        }

        if (scaled !== source && !scaled.isRecycled) {
            scaled.recycle()
        }

        return VisualSignature(
            grayPixels = gray,
            colorHistogram = histogram
        )
    }

    private fun histogramDistance(
        a: DoubleArray,
        b: DoubleArray
    ): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0

        var sum = 0.0
        for (i in 0 until size) {
            sum += abs(a[i] - b[i])
        }

        return (sum / 6.0).coerceIn(0.0, 1.0)
    }

    private fun meanDifference(
        a: IntArray,
        b: IntArray
    ): Double {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0

        var sum = 0L
        for (i in 0 until size) {
            sum += abs(a[i] - b[i])
        }
        return sum.toDouble() / size
    }
}
