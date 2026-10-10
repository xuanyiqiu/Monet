package dev.monet.app.core.di

import android.content.Context
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.network.RemoteMonetApi
import dev.monet.app.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Manual DI root, created once in [dev.monet.app.MonetApp]. */
class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context)

    /**
     * Delegates to fake or remote per call, so flipping the dev switch in Settings takes effect
     * immediately without recreating screens.
     */
    val api: MonetApi = SwitchingMonetApi(
        settingsRepository = settingsRepository,
        fake = FakeMonetApi(),
    )
}

private class SwitchingMonetApi(
    private val settingsRepository: SettingsRepository,
    private val fake: MonetApi,
) : MonetApi {
    private var remote: Pair<String, RemoteMonetApi>? = null

    private suspend fun target(): MonetApi {
        val s = settingsRepository.settings.first()
        if (s.useFakeApi) return fake
        val cached = remote
        if (cached != null && cached.first == s.apiBaseUrl) return cached.second
        // The token is read per request so editing it in Settings applies immediately.
        val api = RemoteMonetApi(s.apiBaseUrl, token = { runBlocking { settingsRepository.settings.first().authToken } })
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
}
