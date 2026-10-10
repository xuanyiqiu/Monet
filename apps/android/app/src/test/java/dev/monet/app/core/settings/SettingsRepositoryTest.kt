package dev.monet.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.monet.app.core.network.Lang
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
            setLinesPerStep(4)
            setThemeMode(ThemeMode.DARK)
            setUseFakeApi(false)
            setApiBaseUrl("http://192.168.1.5:8000")
            setAuthToken("dev:me")
            setOnboardingDone(true)
            setCaptureExplained(true)
        }
        first.cancel()

        val second = CoroutineScope(Dispatchers.IO + Job())
        val s = repo(file, second).settings.first()
        second.cancel()
        assertEquals(Lang.ZH, s.targetLang)
        assertEquals(PinyinStyle.NUMBERED, s.pinyinStyle)
        assertEquals(4, s.linesPerStep)
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
        assertEquals(5, r.settings.first().linesPerStep)
        scope.cancel()
    }
}
