package com.didyar.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object AvalAiVisionProvider {
    const val MODEL = "gemini-3.8-flash"
    const val NO_NEW_VISUAL_MARKER = "__DIDYAR_NO_NEW_VISUAL__"

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
        timecode: String,
        previousDescription: String? = null,
        onRetry: ((String) -> Unit)? = null
    ): String {
        require(apiKey.isNotBlank()) { "کلید API وارد نشده است." }
        require(framesBase64.isNotEmpty()) { "از این صحنه تصویری برای ارسال پیدا نشد." }

        val previousContext = previousDescription
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                """
                توضیح صحنهٔ قبلی این بوده:
                «$it»

                اول بررسی کن آیا صحنهٔ فعلی واقعاً اطلاعات دیداری مهم و تازه‌ای نسبت به آن دارد یا نه.
                اگر فقط همان قاب و همان موقعیت ادامه پیدا کرده، یا تفاوت فقط حرکت کوچک دست و بدن، تکان خوردن اشیا، تغییر جزئی نور، جابه‌جایی کم دوربین، یا دیده نشدن یکی از جزئیات قبلی است، هیچ توضیح تازه‌ای لازم نیست و باید دقیقاً فقط این عبارت را برگردانی:
                $NO_NEW_VISUAL_MARKER
                اگر تغییر مهمی مثل عوض شدن مکان یا قاب اصلی، ورود یا خروج مهم، شروع یک عمل دیداری تازه، نوشتهٔ مهم تازه یا شیء مهم تازه رخ داده، فقط همان اطلاعات تازه و لازم را بگو.
                """.trimIndent()
            }
            .orEmpty()

        val prompt = """
            تو نویسندهٔ توضیح صوتی فارسی برای مخاطب نابینا هستی.
            تصاویر ورودی چند فریم متوالی از یک صحنهٔ فیلم هستند.
            در یک یا دو جملهٔ کوتاه و روان، اطلاعات دیداری مهمی را بگو که احتمالاً از صدای فیلم فهمیده نمی‌شود.

            لحن باید فارسی گفتاریِ طبیعی و مؤدبانه باشد؛ شبیه حرف زدن عادی دو نفر، نه متن گزارش، خبر یا کتاب.
            از ساختارهای خشک مثل «قرار دارد»، «مشاهده می‌شود»، «در حال ... است»، «در دست دارد»، «ایستاده است» و «نشسته است» تا جای ممکن استفاده نکن.
            به جایش طبیعی بنویس؛ مثلاً «کنار میزه»، «داره صحبت می‌کنه»، «کاغذ توی دستشه»، «روی صندلی نشسته» یا «کنارش ایستاده».
            واژه‌ها باید ساده و رایج باشند. از واژه‌های ادبی، قدیمی یا نامأنوس مثل «قبا»، «ردا»، «خرقه»، «جامه» و واژه‌های مشابه استفاده نکن، مگر این‌که هیچ جایگزین ساده و دقیقی نداشته باشند. در حالت عادی بگو «لباس بلند»، «روپوش»، «لباس سنتی» یا توصیف سادهٔ ظاهر لباس.
            جمله‌ها را خیلی شکسته، عامیانهٔ افراطی یا محلی نکن؛ فقط روان و شنیداری باشند.

            رویدادهای دیداری سریع را از دست نده. چند فریم به ترتیب زمانی فرستاده شده‌اند؛ تغییر بین فریم‌ها را هم بررسی کن.
            اگر نتیجهٔ واضح یک اتفاق در فریم بعدی دیده می‌شود، مثل سوراخ شدن شیشه، افتادن یک نفر، شکستن چیزی یا ظاهر شدن اثر برخورد، خودِ نتیجهٔ دیداری را حتماً بگو.
            اگر خودِ لحظهٔ اتفاق بین فریم‌ها دیده نشده، آن لحظه را حدس نزن و فقط چیزی را بگو که واقعاً از تغییر تصاویر معلوم است.

            نوشته‌های مهم داخل تصویر را حفظ کن. اگر عنوان، لوگو، نام، نوشتهٔ روی تابلو، پیام روی گوشی، نام مکان، زمان، یا متن تیتراژ خوانا و واضح است، آن را دقیق بخوان و به‌خاطر کوتاه نگه داشتن توضیح حذفش نکن.
            اگر نوشته واضح نیست، حدس نزن.

            روی حرکت، ورود و خروج افراد، حالت کلی بدن، مکان، اشیای مهم، نوشتهٔ مهم روی تصویر و تغییر بصری اصلی تمرکز کن.
            هویت یا نام افراد را حدس نزن. دربارهٔ نیت یا احساسات قطعی حدس نزن و چیزی را که در تصاویر روشن نیست نساز.
            اگر نوع پرچم، نشان، نوشته، لباس سازمانی، مکان یا هویت دقیق چیزی کاملاً واضح نیست، نام مشخص نبر و فقط ظاهر دیداری آن را توصیف کن.
            دیالوگ‌ها و صداهای قابل شنیدن را بازگو نکن.
            از عبارت‌هایی مثل «در تصویر می‌بینیم» استفاده نکن؛ مستقیم و طبیعی توصیف کن.
            خروجی فقط خود توضیح فارسی باشد، بدون عنوان، شماره‌گذاری یا توضیح اضافه.

            $previousContext

            این صحنه شماره $sceneIndex از $sceneTotal و حوالی زمان $timecode است.
        """.trimIndent()

        val content = JSONArray().put(
            JSONObject().apply {
                put("type", "text")
                put("text", prompt)
            }
        )

        framesBase64.take(5).forEach { frame ->
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
            put("max_tokens", 360)
            put("reasoning_effort", "none")
            put("temperature", 0.2)
        }

        return requestWithFallback(apiKey, payload, onRetry)
    }

    private fun requestWithFallback(
        apiKey: String,
        payload: JSONObject,
        onRetry: ((String) -> Unit)? = null
    ): String {
        var lastNetworkError: Exception? = null

        endpoints.forEach { endpoint ->
            try {
                return requestWithOneRetry(endpoint, apiKey, payload, onRetry)
            } catch (e: IOException) {
                lastNetworkError = e
            }
        }

        throw lastNetworkError ?: IllegalStateException("ارتباط با سرویس AvalAI برقرار نشد.")
    }

    private fun requestWithOneRetry(
        endpoint: String,
        apiKey: String,
        payload: JSONObject,
        onRetry: ((String) -> Unit)? = null
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
                val insufficientCredit =
                    e.statusCode == 429 &&
                        (e.message?.contains("insufficient credit", ignoreCase = true) == true ||
                            e.message?.contains("remaining balance", ignoreCase = true) == true)

                val transient =
                    !insufficientCredit &&
                        (e.statusCode == 429 || e.statusCode in 500..599)
                val maxAttempts = 1
                if (!transient || transientAttempts >= maxAttempts) throw e

                val waitMs = if (e.statusCode == 429) {
                    val serverWaitMs = e.retryAfterSeconds
                        ?.coerceIn(1L, 60L)
                        ?.times(1_000L)
                    serverWaitMs ?: 45_000L
                } else {
                    5_000L
                }

                transientAttempts += 1
                val waitSeconds = (waitMs / 1_000L).coerceAtLeast(1L)
                val retryMessage = if (e.statusCode == 429) {
                    "AvalAI محدودیت موقت داده؛ $waitSeconds ثانیه دیگر یک بار دوباره تلاش می‌کنم."
                } else {
                    "AvalAI موقتاً پاسخ مناسب نداده؛ $waitSeconds ثانیه دیگر یک بار دوباره تلاش می‌کنم."
                }
                onRetry?.invoke(retryMessage)
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

                val userMessage = if (
                    status == 429 &&
                    (message.contains("insufficient credit", ignoreCase = true) ||
                        message.contains("remaining balance", ignoreCase = true))
                ) {
                    "اعتبار حساب AvalAI برای این درخواست کافی نیست. لطفاً اعتبار حساب را افزایش دهید."
                } else if (message.isNotBlank()) {
                    "AvalAI خطای $status: $message"
                } else {
                    "AvalAI خطای $status برگرداند."
                }

                throw HttpStatusException(
                    statusCode = status,
                    retryAfterSeconds = retryAfterSeconds,
                    message = userMessage
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
