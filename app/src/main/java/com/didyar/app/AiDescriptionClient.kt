package com.didyar.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AvalAiVisionProvider {
    const val MODEL = "gemini-3.8-flash"

    private val endpoints = listOf(
        "https://api.avalai.ir/v1/chat/completions",
        "https://api.avalapis.ir/v1/chat/completions",
        "https://api.avalai.org/v1/chat/completions"
    )

    private class HttpStatusException(
        val statusCode: Int,
        val retryAfterSeconds: Long?,
        message: String
    ) : IllegalStateException(message)

    private class EmptyOutputException(
        val finishReason: String?
    ) : IllegalStateException(
        if (finishReason == "length") {
            "مدل به سقف خروجی رسید و متن نهایی تولید نشد."
        } else {
            "متن توضیح از AvalAI دریافت نشد."
        }
    )

    fun testConnection(apiKey: String): String {
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", "فقط کلمهٔ آماده را بنویس.")
                }
            ))
            put("max_tokens", 96)
            put("reasoning_effort", "none")
            put("temperature", 0)
        }
        return requestWithFallback(apiKey, payload)
    }

    fun describeScene(
        apiKey: String,
        framesBase64: List<String>,
        sceneIndex: Int,
        sceneTotal: Int,
        timecode: String
    ): String {
        require(apiKey.isNotBlank()) { "کلید API وارد نشده است." }
        require(framesBase64.isNotEmpty()) { "از این صحنه تصویری برای ارسال پیدا نشد." }

        val prompt = """
            تو نویسندهٔ توضیح صوتی فارسی برای مخاطب نابینا هستی.
            تصاویر ورودی چند فریم متوالی از یک صحنهٔ فیلم هستند.
            در یک یا دو جملهٔ کوتاه و روان فقط اطلاعات دیداری مهمی را بگو که احتمالاً از صدای فیلم فهمیده نمی‌شود.
            لحن فارسی باید طبیعی، شنیداری و خودمانیِ ملایم باشد؛ مثل حرف زدن روزمرهٔ مودبانه، نه زبان خشک و کتابی و نه اصطلاحات خیلی کوچه‌بازاری.
            روی حرکت، ورود و خروج افراد، حالت کلی بدن، مکان، اشیای مهم، نوشتهٔ مهم روی تصویر و تغییر بصری اصلی تمرکز کن.
            هویت یا نام افراد را حدس نزن. دربارهٔ نیت یا احساسات قطعی حدس نزن و چیزی را که در تصاویر روشن نیست نساز.
            اگر نوع پرچم، نشان، نوشته، لباس سازمانی، مکان یا هویت دقیق چیزی کاملاً واضح نیست، نام مشخص نبر و فقط ظاهر دیداری آن را توصیف کن.
            دیالوگ‌ها و صداهای قابل شنیدن را بازگو نکن.
            از عبارت‌هایی مثل «در تصویر می‌بینیم» استفاده نکن؛ مستقیم و طبیعی توصیف کن.
            خروجی فقط خود توضیح فارسی باشد، بدون عنوان، شماره‌گذاری یا توضیح اضافه.
            این صحنه شماره $sceneIndex از $sceneTotal و حوالی زمان $timecode است.
        """.trimIndent()

        val content = JSONArray().put(
            JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            }
        )

        framesBase64.take(3).forEach { frame ->
            content.put(
                JSONObject().apply {
                    put("type", "image_url")
                    put(
                        "image_url",
                        JSONObject().apply {
                            put("url", "data:image/jpeg;base64,$frame")
                            put("detail", "low")
                        }
                    )
                }
            )
        }

        val payload = JSONObject().apply {
            put("model", MODEL)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", content)
                    }
                )
            )
            put("max_tokens", 320)
            put("reasoning_effort", "none")
            put("temperature", 0.2)
        }

        return requestWithFallback(apiKey, payload)
    }

    private fun requestWithFallback(apiKey: String, payload: JSONObject): String {
        var lastNetworkError: Exception? = null

        endpoints.forEach { endpoint ->
            try {
                return requestWithOneRetry(endpoint, apiKey, payload)
            } catch (e: IOException) {
                lastNetworkError = e
            }
        }

        throw lastNetworkError ?: IllegalStateException("ارتباط با سرویس AvalAI برقرار نشد.")
    }

    private fun requestWithOneRetry(
        endpoint: String,
        apiKey: String,
        payload: JSONObject
    ): String {
        var workingPayload = JSONObject(payload.toString())
        var emptyOutputRetried = false
        var transientAttempts = 0

        while (true) {
            try {
                return request(endpoint, apiKey, workingPayload)
            } catch (e: EmptyOutputException) {
                if (emptyOutputRetried) throw e
                emptyOutputRetried = true
                workingPayload = JSONObject(workingPayload.toString()).apply {
                    put("reasoning_effort", "none")
                    put("max_tokens", maxOf(512, optInt("max_tokens", 0) * 2))
                }
            } catch (e: HttpStatusException) {
                val transient = e.statusCode == 429 || e.statusCode in 500..599
                val maxAttempts = if (e.statusCode == 429) 4 else 2
                if (!transient || transientAttempts >= maxAttempts) throw e

                val waitMs = if (e.statusCode == 429) {
                    val serverWait = e.retryAfterSeconds?.times(1_000L)
                    serverWait ?: when (transientAttempts) {
                        0 -> 30_000L
                        1 -> 60_000L
                        2 -> 90_000L
                        else -> 120_000L
                    }
                } else {
                    3_000L * (transientAttempts + 1)
                }

                transientAttempts += 1
                Thread.sleep(waitMs)
            }
        }
    }

    private fun request(endpoint: String, apiKey: String, payload: JSONObject): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 25_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        }

        try {
            connection.outputStream.use { output ->
                output.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)
                ?.use(BufferedReader::readText)
                .orEmpty()

            if (status !in 200..299) {
                val message = runCatching {
                    val root = JSONObject(responseText)
                    when (val error = root.opt("error")) {
                        is JSONObject -> error.optString("message").ifBlank {
                            error.optString("code")
                        }
                        is String -> error
                        else -> ""
                    }
                }.getOrNull().orEmpty()

                val retryAfterSeconds =
                    connection.getHeaderField("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()

                throw HttpStatusException(
                    statusCode = status,
                    retryAfterSeconds = retryAfterSeconds,
                    message = if (message.isNotBlank()) {
                        "AvalAI خطای $status: $message"
                    } else {
                        "AvalAI خطای $status برگرداند."
                    }
                )
            }

            val root = JSONObject(responseText)
            val choices = root.optJSONArray("choices")
                ?: throw IllegalStateException("پاسخ AvalAI ساختار مورد انتظار را ندارد.")
            if (choices.length() == 0) {
                throw IllegalStateException("AvalAI پاسخی برنگرداند.")
            }

            val choice = choices.getJSONObject(0)
            val finishReason = choice.optString("finish_reason").takeIf { it.isNotBlank() }
            val message = choice.getJSONObject("message")
            val messageContent = message.opt("content")

            val text = when (messageContent) {
                is String -> messageContent
                is JSONArray -> buildString {
                    for (i in 0 until messageContent.length()) {
                        val item = messageContent.optJSONObject(i) ?: continue
                        val t = item.optString("text")
                        if (t.isNotBlank()) append(t)
                    }
                }
                else -> ""
            }.trim()

            if (text.isBlank()) {
                throw EmptyOutputException(finishReason)
            }

            return text
        } finally {
            connection.disconnect()
        }
    }
}
