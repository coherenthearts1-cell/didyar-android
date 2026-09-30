package com.didyar.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class AiServiceConfig(
    val endpoint: String,
    val accessToken: String
)

object AiDescriptionClient {
    fun describeScene(
        config: AiServiceConfig,
        framesBase64: List<String>,
        sceneIndex: Int,
        sceneTotal: Int,
        timecode: String
    ): String {
        require(config.endpoint.startsWith("https://")) {
            "نشانی سرویس باید با https:// شروع شود."
        }
        require(framesBase64.isNotEmpty()) {
            "از این صحنه تصویری برای ارسال پیدا نشد."
        }

        val body = JSONObject().apply {
            put("scene_index", sceneIndex)
            put("scene_total", sceneTotal)
            put("timecode", timecode)
            put("frames", JSONArray(framesBase64))
        }

        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (config.accessToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.accessToken.trim()}")
            }
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val responseText = stream?.bufferedReader(Charsets.UTF_8)
                ?.use(BufferedReader::readText)
                .orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText).optString("error")
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    if (message.isNotBlank()) message
                    else "سرویس هوش مصنوعی خطای $status برگرداند."
                )
            }

            val description = JSONObject(responseText)
                .optString("description")
                .trim()

            if (description.isBlank()) {
                throw IllegalStateException("سرویس توضیح خالی برگرداند.")
            }

            return description
        } finally {
            connection.disconnect()
        }
    }
}
