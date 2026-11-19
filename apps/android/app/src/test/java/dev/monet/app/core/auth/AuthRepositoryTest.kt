package dev.monet.app.core.auth

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AuthRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    @After fun tearDown() = scope.cancel()

    private val now = 1_000L

    /** Fake HTTP layer: [handler] sees each request and returns (status, body) or throws IOException. */
    private fun repo(store: SessionStore, handler: (Request) -> Pair<Int, String>): AuthRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val api = SupabaseAuthApi("https://example.supabase.co", "sb_publishable_test", client) { now }
        return AuthRepository(api, store) { now }
    }

    private fun store() = SessionStore(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "auth.preferences_pb") })

    private fun token(access: String, refresh: String) =
        """{"access_token":"$access","refresh_token":"$refresh","expires_in":3600}"""

    @Test
    fun `sign in stores the session and reports signed in`() = runBlocking {
        val store = store()
        val repo = repo(store) { req ->
            assertTrue(req.url.toString().endsWith("/auth/v1/token?grant_type=password"))
            assertEquals("sb_publishable_test", req.header("apikey"))
            200 to token("a1", "r1")
        }
        repo.signIn("  me@example.com ", "pw")
        assertEquals(Session("a1", "r1", now + 3600), store.current())
        assertEquals(AuthState.SignedIn, repo.state.first())
    }

    @Test
    fun `wrong password raises INVALID_CREDENTIALS and stores nothing`() = runBlocking {
        val store = store()
        val repo = repo(store) { 400 to """{"error_code":"invalid_credentials","msg":"Invalid login credentials"}""" }
        try {
            repo.signIn("me@example.com", "nope")
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(AuthException.Kind.INVALID_CREDENTIALS, e.kind)
        }
        assertNull(store.current())
        assertEquals(AuthState.SignedOut, repo.state.first())
    }

    @Test
    fun `a refused refresh token signs the user out`() = runBlocking {
        val store = store().also { it.save(Session("old", "dead", 0)) }
        val repo = repo(store) { 400 to """{"error_code":"refresh_token_not_found","msg":"Invalid Refresh Token"}""" }
        assertNull(repo.accessToken(force = true))
        assertNull(store.current())
    }

    @Test
    fun `forcing a refresh replaces both tokens`() = runBlocking {
        val store = store().also { it.save(Session("old", "r-old", now + 10_000)) }
        val repo = repo(store) { req ->
            assertTrue(req.url.toString().endsWith("grant_type=refresh_token"))
            200 to token("new", "r-new")
        }
        assertEquals("new", repo.accessToken(force = true))
        assertEquals("r-new", store.current()?.refreshToken)
    }

    @Test
    fun `offline with a still-valid token keeps the session and the old token`() = runBlocking {
        val store = store().also { it.save(Session("still-good", "r1", now + 600)) }
        val repo = repo(store) { throw IOException("no network") }
        assertEquals("still-good", repo.accessToken())
        assertEquals("r1", store.current()?.refreshToken)
    }

    @Test
    fun `offline with an expired token fails loudly but keeps the session`() = runBlocking {
        val store = store().also { it.save(Session("stale", "r1", now - 5)) }
        val repo = repo(store) { throw IOException("no network") }
        try {
            repo.accessToken()
            fail("expected AuthException")
        } catch (e: AuthException) {
            assertEquals(AuthException.Kind.NETWORK, e.kind)
        }
        assertEquals("r1", store.current()?.refreshToken)
    }

    @Test
    fun `nobody signed in means no token and no network call`() = runBlocking {
        val repo = repo(store()) { fail("must not call the network"); 500 to "" }
        assertNull(repo.accessToken())
    }

    @Test
    fun `sign out wipes the stored session`() = runBlocking {
        val store = store().also { it.save(Session("a", "r", 1)) }
        repo(store) { 500 to "" }.signOut()
        assertNull(store.current())
    }

    @Test
    fun `token response uses expires_at when present and expires_in otherwise`() {
        assertEquals(9_999L, TokenResponse("a", "r", expiresIn = 3600, expiresAt = 9_999).toSession(1_000).expiresAtEpochSec)
        assertEquals(4_600L, TokenResponse("a", "r", expiresIn = 3600).toSession(1_000).expiresAtEpochSec)
    }

    @Test
    fun `stored preferences map to a session only when both tokens exist`() {
        assertNull(emptyPreferences().toSession())
        assertNull(mutablePreferencesOf(stringPreferencesKey("access_token") to "a").toSession())
    }
}
