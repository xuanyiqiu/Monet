package dev.monet.app.core.settings

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.monet.app.core.network.Lang
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
        assertEquals(5, s.linesPerStep)
    }
}
