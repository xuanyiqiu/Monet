package dev.monet.app.core.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import dev.monet.app.feature.lens.capture.LINES_ALL
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMappingTest {

    @Test
    fun `a fresh install has not onboarded and has not seen the capture notice`() {
        val s = mutablePreferencesOf().toSettings()
        org.junit.Assert.assertFalse(s.onboardingDone)
        org.junit.Assert.assertFalse(s.captureExplained)
    }

    @Test
    fun `empty preferences yield defaults`() {
        assertEquals(Settings(), mutablePreferencesOf().toSettings())
    }

    @Test
    fun `stored values map to settings`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("target_lang") to "ES",
            stringPreferencesKey("pinyin_style") to "NUMBERED",
            intPreferencesKey("lines_per_step") to 3,
            stringPreferencesKey("theme_mode") to "DARK",
        )
        val s = prefs.toSettings()
        assertEquals(Lang.ES, s.targetLang)
        assertEquals(PinyinStyle.NUMBERED, s.pinyinStyle)
        assertEquals(3, s.linesPerStep)
        assertEquals(ThemeMode.DARK, s.themeMode)
    }

    @Test
    fun `unknown enum names and out of range values fall back safely`() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("target_lang") to "KLINGON",
            intPreferencesKey("lines_per_step") to 99,
        )
        val s = prefs.toSettings()
        assertEquals(Lang.EN, s.targetLang)
        assertEquals(10, s.linesPerStep)
    }

    @Test
    fun `new settings default to auto source, 60 percent, normal chips and normal speech`() {
        val s = mutablePreferencesOf().toSettings()
        assertEquals(SourceLang.AUTO, s.sourceLang)
        assertEquals(60, s.overlayOpacity)
        assertEquals(ChipSize.NORMAL, s.chipSize)
        assertEquals(TtsSpeed.NORMAL, s.ttsSpeed)
        assertEquals(2, s.linesPerStep)
    }

    @Test
    fun `new stored values map to settings`() {
        val s = mutablePreferencesOf(
            stringPreferencesKey("source_lang") to "ES",
            intPreferencesKey("overlay_opacity") to 100,
            stringPreferencesKey("chip_size") to "LARGE",
            stringPreferencesKey("tts_speed") to "FAST",
            intPreferencesKey("lines_per_step") to LINES_ALL,
        ).toSettings()
        assertEquals(SourceLang.ES, s.sourceLang)
        assertEquals(100, s.overlayOpacity)
        assertEquals(ChipSize.LARGE, s.chipSize)
        assertEquals(TtsSpeed.FAST, s.ttsSpeed)
        assertEquals(LINES_ALL, s.linesPerStep)
    }

    @Test
    fun `old lines per step 4 migrates to a valid option`() {
        val s = mutablePreferencesOf(intPreferencesKey("lines_per_step") to 4).toSettings()
        assertTrue(s.linesPerStep in LINES_OPTIONS)
        assertEquals(3, s.linesPerStep)
    }

    @Test
    fun `garbage values fall back for new settings`() {
        val s = mutablePreferencesOf(
            stringPreferencesKey("source_lang") to "XX",
            intPreferencesKey("overlay_opacity") to -5,
            stringPreferencesKey("chip_size") to "HUGE",
            intPreferencesKey("lines_per_step") to 0,
        ).toSettings()
        assertEquals(SourceLang.AUTO, s.sourceLang)
        assertEquals(40, s.overlayOpacity)
        assertEquals(ChipSize.NORMAL, s.chipSize)
        assertEquals(1, s.linesPerStep)
    }
}
