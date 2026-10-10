package dev.monet.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.monet.app.core.network.Lang
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class PinyinStyle { TONE_MARKS, TONE_COLORS, NUMBERED, OFF }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val targetLang: Lang = Lang.EN,
    val pinyinStyle: PinyinStyle = PinyinStyle.TONE_COLORS,
    val linesPerStep: Int = 2,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Dev switch: use [dev.monet.app.core.network.FakeMonetApi] instead of the server. */
    val useFakeApi: Boolean = true,
    val apiBaseUrl: String = "http://10.0.2.2:8000",
    val authToken: String = "dev:android",
    val onboardingDone: Boolean = false,
    /** The "Android will ask to capture your screen" notice was shown once. */
    val captureExplained: Boolean = false,
)

/** Pure mapping so it can be unit tested without Android. */
internal fun Preferences.toSettings(): Settings {
    val d = Settings()
    return Settings(
        targetLang = enumOrDefault(this[Keys.targetLang], d.targetLang),
        pinyinStyle = enumOrDefault(this[Keys.pinyinStyle], d.pinyinStyle),
        linesPerStep = (this[Keys.linesPerStep] ?: d.linesPerStep).coerceIn(1, 5),
        themeMode = enumOrDefault(this[Keys.themeMode], d.themeMode),
        useFakeApi = this[Keys.useFakeApi] ?: d.useFakeApi,
        apiBaseUrl = this[Keys.apiBaseUrl] ?: d.apiBaseUrl,
        authToken = this[Keys.authToken] ?: d.authToken,
        onboardingDone = this[Keys.onboardingDone] ?: d.onboardingDone,
        captureExplained = this[Keys.captureExplained] ?: d.captureExplained,
    )
}

private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

private object Keys {
    val targetLang = stringPreferencesKey("target_lang")
    val pinyinStyle = stringPreferencesKey("pinyin_style")
    val linesPerStep = intPreferencesKey("lines_per_step")
    val themeMode = stringPreferencesKey("theme_mode")
    val useFakeApi = booleanPreferencesKey("use_fake_api")
    val apiBaseUrl = stringPreferencesKey("api_base_url")
    val authToken = stringPreferencesKey("auth_token")
    val onboardingDone = booleanPreferencesKey("onboarding_done")
    val captureExplained = booleanPreferencesKey("capture_explained")
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.dataStore)

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun setTargetLang(value: Lang) = store.edit { it[Keys.targetLang] = value.name }
    suspend fun setPinyinStyle(value: PinyinStyle) = store.edit { it[Keys.pinyinStyle] = value.name }
    suspend fun setLinesPerStep(value: Int) = store.edit { it[Keys.linesPerStep] = value.coerceIn(1, 5) }
    suspend fun setThemeMode(value: ThemeMode) = store.edit { it[Keys.themeMode] = value.name }
    suspend fun setUseFakeApi(value: Boolean) = store.edit { it[Keys.useFakeApi] = value }
    suspend fun setApiBaseUrl(value: String) = store.edit { it[Keys.apiBaseUrl] = value }
    suspend fun setAuthToken(value: String) = store.edit { it[Keys.authToken] = value }
    suspend fun setOnboardingDone(value: Boolean) = store.edit { it[Keys.onboardingDone] = value }
    suspend fun setCaptureExplained(value: Boolean) = store.edit { it[Keys.captureExplained] = value }
}
