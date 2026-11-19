package dev.monet.app.feature.detail

import android.content.Context
import android.speech.tts.TextToSpeech
import dev.monet.app.core.network.Lang
import dev.monet.app.core.settings.TtsSpeed
import dev.monet.app.core.settings.speechRate
import java.util.Locale

/** Thin wrapper over [TextToSpeech]; speaks in the text's own language, optionally slowed down. */
class Speaker(context: Context) {
    /** Updated from Settings; [speak] reads it each time. */
    @Volatile var speed: TtsSpeed = TtsSpeed.NORMAL
    /** Decides when a second tap on Listen means "slower". */
    val taps = PlayTracker()
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    /** @return false when the engine is not ready or has no voice for [lang]. */
    fun speak(text: String, lang: Lang, slow: Boolean): Boolean {
        if (!ready) return false
        val result = tts.setLanguage(lang.toLocale())
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setSpeechRate(speechRate(speed, slow))
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

/**
 * A second tap on Listen while the first is still being spoken replays slowly; a third goes back to
 * normal. How long speech lasts is estimated from the text length, since the engine is not polled.
 */
class PlayTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private var playingUntil = 0L

    /** @return true when this tap should play slowly. */
    fun tap(textLength: Int): Boolean {
        val now = clock()
        if (now < playingUntil) {
            playingUntil = 0L
            return true
        }
        playingUntil = now + maxOf(1500L, textLength * 250L)
        return false
    }

    /** Any other way of speaking (Slow button, a token) ends the "playing" window. */
    fun reset() {
        playingUntil = 0L
    }
}
