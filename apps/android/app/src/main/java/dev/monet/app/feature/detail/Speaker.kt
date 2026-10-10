package dev.monet.app.feature.detail

import android.content.Context
import android.speech.tts.TextToSpeech
import dev.monet.app.core.network.Lang
import java.util.Locale

/** Thin wrapper over [TextToSpeech]; speaks in the text's own language, optionally slowed down. */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    /** @return false when the engine is not ready or has no voice for [lang]. */
    fun speak(text: String, lang: Lang, slow: Boolean): Boolean {
        if (!ready) return false
        val result = tts.setLanguage(lang.toLocale())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setSpeechRate(if (slow) 0.55f else 1.0f)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "monet-detail")
        return true
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private fun Lang.toLocale(): Locale = when (this) {
        Lang.ZH -> Locale.SIMPLIFIED_CHINESE
        Lang.ES -> Locale("es", "ES")
        Lang.EN -> Locale.US
    }
}
