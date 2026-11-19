package dev.monet.app.feature.detail

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.monet.app.core.data.NotebookRepository
import dev.monet.app.core.data.notebookId
import dev.monet.app.core.network.ApiException
import dev.monet.app.core.network.FakeMonetApi
import dev.monet.app.core.network.FeedbackRequest
import dev.monet.app.core.network.MonetApi
import dev.monet.app.core.network.Problem
import dev.monet.app.core.settings.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetailControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + Job())
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var settings: SettingsRepository
    private lateinit var notebook: NotebookRepository

    private val feedback = mutableListOf<FeedbackRequest>()
    private val feedbackCalls = AtomicInteger()
    private var feedbackError: ApiException? = null

    private val api: MonetApi = object : MonetApi by FakeMonetApi(latencyMs = 0) {
        override suspend fun sendFeedback(request: FeedbackRequest) {
            feedbackCalls.incrementAndGet()
            feedbackError?.let { throw it }
            feedback += request
        }
    }

    @Before
    fun setUp() {
        settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope) { File(tmp.root, "settings.preferences_pb") })
        notebook = NotebookRepository(File(tmp.root, "notebook.json"))
    }

    @After
    fun tearDown() = runBlocking {
        scope.coroutineContext[Job]!!.cancelAndJoin()
        storeScope.coroutineContext[Job]!!.cancelAndJoin()
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    private suspend fun ready(controller: DetailController) = controller.state.await { it is DetailUiState.Ready } as DetailUiState.Ready

    @Test
    fun `save puts the explanation in the notebook and flips the saved flag`() = runBlocking {
        val controller = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        val a = ready(controller).annotation
        assertFalse(controller.extras.await { true }.saved)

        controller.save()
        assertTrue(controller.extras.await { it.saved }.saved)
        assertEquals(listOf(notebookId(a)), notebook.entries.value.map { it.id })
    }

    @Test
    fun `saving twice keeps a single notebook entry`() = runBlocking {
        val controller = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        ready(controller)
        controller.save()
        controller.extras.await { it.saved }
        controller.save()
        controller.save()
        notebook.load()
        assertEquals(1, notebook.entries.value.size)
    }

    @Test
    fun `a line saved earlier opens already marked as saved`() = runBlocking {
        val first = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        val a = ready(first).annotation
        notebook.save(a)

        val again = DetailController(scope, api, settings, a.text, notebook)
        ready(again)
        assertTrue(again.extras.await { it.saved }.saved)
    }

    @Test
    fun `a provided annotation is shown without any network call`() = runBlocking {
        val a = FakeMonetApi(latencyMs = 0).annotate(dev.monet.app.core.network.AnnotateRequest(text = "你好", targetLang = dev.monet.app.core.network.Lang.EN))
        val calls = AtomicInteger()
        val counting: MonetApi = object : MonetApi by FakeMonetApi(latencyMs = 0) {
            override suspend fun annotate(request: dev.monet.app.core.network.AnnotateRequest): dev.monet.app.core.network.Annotation {
                calls.incrementAndGet()
                return a
            }
        }
        val controller = DetailController(scope, counting, settings, a.text, notebook, provided = a)
        assertEquals(a, ready(controller).annotation)
        assertEquals(0, calls.get())
    }

    @Test
    fun `report sends the text and its translation once`() = runBlocking {
        val controller = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        val a = ready(controller).annotation
        controller.report()
        controller.extras.await { it.report == ReportState.Sent }
        controller.report()
        controller.report()

        assertEquals(1, feedbackCalls.get())
        assertEquals(FeedbackRequest(text = "他这样做简直是画蛇添足。", translation = a.translation, targetLang = a.targetLang), feedback.single())
    }

    @Test
    fun `a failed report shows an error and can be retried`() = runBlocking {
        val controller = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        ready(controller)
        feedbackError = ApiException(null)
        controller.report()
        val failed = controller.extras.await { it.report is ReportState.Failed }.report as ReportState.Failed
        assertEquals("Can't reach the server.", failed.message)

        feedbackError = null
        controller.report()
        controller.extras.await { it.report == ReportState.Sent }
        assertEquals(2, feedbackCalls.get())
    }

    @Test
    fun `a server problem reports its detail`() = runBlocking {
        val controller = DetailController(scope, api, settings, "他这样做简直是画蛇添足。", notebook)
        ready(controller)
        feedbackError = ApiException(Problem(title = "Too many", status = 429, detail = "Slow down"))
        controller.report()
        val failed = controller.extras.await { it.report is ReportState.Failed }.report as ReportState.Failed
        assertEquals("Slow down", failed.message)
    }
}
