package com.didyar.app

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

object NetarzAccountClient {
    fun testConnection(apiKey: String): String {
        require(apiKey.isNotBlank()) { "کلید API وارد نشده است." }
        val connection = (URL("https://netarz.ir/api/ai/v1/me").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            val headerName = "Author" + "ization"
            setRequestProperty(headerName, "Bearer " + apiKey.trim())
        }

        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    val root = JSONObject(responseText)
                    when (val error = root.opt("error")) {
                        is JSONObject -> error.optString("message").ifBlank { error.optString("code") }
                        is String -> error
                        else -> root.optString("message")
                    }
                }.getOrNull().orEmpty()
                throw IllegalStateException(
                    if (message.isNotBlank()) "نِت‌اَرز خطای $status: $message"
                    else "نِت‌اَرز خطای $status برگرداند."
                )
            }

            val root = JSONObject(responseText)
            val balance = root.optJSONObject("balance")
            val display = balance?.optString("usd_display")?.takeIf { it.isNotBlank() }
                ?: balance?.optString("usd")?.takeIf { it.isNotBlank() }?.let { "$" + it }
            val accountStatus = root.optString("status")
            return when {
                accountStatus.isNotBlank() && !accountStatus.equals("active", ignoreCase = true) ->
                    "کلید معتبر است، اما وضعیت حساب: $accountStatus."
                display != null -> "کلید معتبر است. موجودی API: $display."
                else -> "کلید معتبر است و حساب پاسخ داد."
            }
        } finally {
            connection.disconnect()
        }
    }
}
