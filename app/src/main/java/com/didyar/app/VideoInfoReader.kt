package com.didyar.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

data class VideoInfo(
    val durationMs: Long,
    val title: String?
)

object VideoInfoReader {
    fun read(context: Context, uri: Uri): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            VideoInfo(durationMs = duration, title = title)
        } finally {
            retriever.release()
        }
    }
}
