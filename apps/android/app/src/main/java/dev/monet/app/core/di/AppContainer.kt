package dev.monet.app.core.di

import android.content.Context
import dev.monet.app.BuildConfig
import dev.monet.app.core.auth.AuthRepository
import dev.monet.app.core.auth.SessionStore
import dev.monet.app.core.auth.SupabaseAuthApi
import dev.monet.app.core.data.AnnotationCache
import dev.monet.app.core.data.CachingMonetApi
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.network.RemoteMonetApi
import dev.monet.app.core.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Manual DI root, created once in [dev.monet.app.MonetApp]. */
class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)

    val auth = AuthRepository(
        api = SupabaseAuthApi(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY),
        store = SessionStore(context),
    )

    private val annotations = AnnotationCache(File(context.cacheDir, "annotations"))

    /**
     * Delegates to fake or remote per call, so flipping the dev switch in Settings takes effect
     * immediately without recreating screens.
     */
    val api: MonetApi = SwitchingMonetApi(
        settingsRepository = settingsRepository,
        auth = auth,
        fake = FakeMonetApi(),
        cache = annotations,
    )
}

private class SwitchingMonetApi(
    private val settingsRepository: SettingsRepository,
    private val auth: AuthRepository,
    private val fake: MonetApi,
    private val cache: AnnotationCache,
) : MonetApi {
    private var remote: Pair<String, MonetApi>? = null

    private suspend fun target(): MonetApi {
        val s = settingsRepository.settings.first()
        if (s.useFakeApi) return fake
        val cached = remote
        if (cached != null && cached.first == s.apiBaseUrl) return cached.second
        // Both lambdas run on OkHttp threads, so blocking here is fine. The signed-in session wins;
        // the Settings token is the fallback for a local dev API that accepts `dev:` tokens.
        val api = CachingMonetApi(
            RemoteMonetApi(
                baseUrl = s.apiBaseUrl,
                token = {
                    runBlocking { auth.accessToken() ?: settingsRepository.settings.first().authToken }
                },
                renewToken = { runBlocking { auth.accessToken(force = true) } },
            ),
            cache,
        )
        remote = s.apiBaseUrl to api
        return api
    }

    override suspend fun health() = target().health()
    override suspend fun me() = target().me()
    override suspend fun annotate(request: dev.monet.app.core.network.AnnotateRequest) = target().annotate(request)
    override suspend fun annotateLines(request: dev.monet.app.core.network.AnnotateLinesRequest) =
        target().annotateLines(request)
    override suspend fun sendFeedback(request: dev.monet.app.core.network.FeedbackRequest) =
        target().sendFeedback(request)
    override suspend fun listDocuments() = target().listDocuments()
    override suspend fun getDocument(id: String) = target().getDocument(id)
    override suspend fun deleteDocument(id: String) = target().deleteDocument(id)
    override suspend fun getDocumentPage(id: String, index: Int) = target().getDocumentPage(id, index)
    override suspend fun uploadDocument(
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        targetLang: dev.monet.app.core.network.Lang,
        sourceLang: dev.monet.app.core.network.SourceLang,
        title: String?,
    ) = target().uploadDocument(fileName, mimeType, bytes, targetLang, sourceLang, title)
    override suspend fun downloadDocument(id: String) = target().downloadDocument(id)
}
