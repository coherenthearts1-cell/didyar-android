package com.didyar.app

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class PersianTts(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("fa", "IR"))
            ready = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            if (!ready) {
                tts.language = Locale.getDefault()
                ready = true
            }
        }
    }

    fun speak(text: String): Boolean {
        if (!ready || text.isBlank()) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "didyar-description")
        return true
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
