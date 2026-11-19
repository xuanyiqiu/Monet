package dev.monet.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.monet.app.core.network.Lang
import dev.monet.app.core.network.SourceLang
import dev.monet.app.feature.lens.capture.LINES_ALL
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(file: File, scope: CoroutineScope) =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun `values persist across repository instances`() = runBlocking {
        val file = File(tmp.root, "settings.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + Job())
        repo(file, first).apply {
            setTargetLang(Lang.ZH)
            setPinyinStyle(PinyinStyle.NUMBERED)
            setLinesPerStep(5)
            setSourceLang(SourceLang.ZH)
            setOverlayOpacity(80)
            setChipSize(ChipSize.SMALL)
            setTtsSpeed(TtsSpeed.SLOW)
            setThemeMode(ThemeMode.DARK)
            setUseFakeApi(false)
            setApiBaseUrl("http://192.168.1.5:8000")
            setAuthToken("dev:me")
            setOnboardingDone(true)
            setCaptureExplained(true)
        }
        first.coroutineContext[Job]!!.cancelAndJoin()

        val second = CoroutineScope(Dispatchers.IO + Job())
        val s = repo(file, second).settings.first()
        second.cancel()
        assertEquals(Lang.ZH, s.targetLang)
        assertEquals(PinyinStyle.NUMBERED, s.pinyinStyle)
        assertEquals(5, s.linesPerStep)
        assertEquals(SourceLang.ZH, s.sourceLang)
        assertEquals(80, s.overlayOpacity)
        assertEquals(ChipSize.SMALL, s.chipSize)
        assertEquals(TtsSpeed.SLOW, s.ttsSpeed)
        assertEquals(ThemeMode.DARK, s.themeMode)
        assertFalse(s.useFakeApi)
        assertEquals("http://192.168.1.5:8000", s.apiBaseUrl)
        assertEquals("dev:me", s.authToken)
        assertTrue(s.onboardingDone)
        assertTrue(s.captureExplained)
    }

    @Test
    fun `lines per step is clamped on write`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val r = repo(File(tmp.root, "clamp.preferences_pb"), scope)
        r.setLinesPerStep(0)
        assertEquals(1, r.settings.first().linesPerStep)
        r.setLinesPerStep(42)
        assertEquals(10, r.settings.first().linesPerStep)
        r.setLinesPerStep(LINES_ALL)
        assertEquals(LINES_ALL, r.settings.first().linesPerStep)
        scope.cancel()
    }

    @Test
    fun `opacity is snapped on write and defaults apply when unset`() = runBlocking {
        val file = File(tmp.root, "opacity.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val r = repo(file, scope)
        assertEquals(SourceLang.AUTO, r.settings.first().sourceLang)
        assertEquals(60, r.settings.first().overlayOpacity)
        r.setOverlayOpacity(77)
        assertEquals(80, r.settings.first().overlayOpacity)
        scope.coroutineContext[Job]!!.cancelAndJoin()
    }
}
