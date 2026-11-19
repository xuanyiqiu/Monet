package dev.monet.app.core.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single owner of the signed-in session. Everything that needs a token asks [accessToken], which
 * renews it when needed. The mutex makes concurrent requests share one renewal instead of racing
 * (a refresh token may be single-use, so two parallel renewals could invalidate each other).
 */
class AuthRepository(
    private val api: SupabaseAuthApi,
    private val store: SessionStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    val state: Flow<AuthState> = store.session.map { if (it == null) AuthState.SignedOut else AuthState.SignedIn }

    private val lock = Mutex()

    /** Throws [AuthException] on wrong credentials or no connection. */
    suspend fun signIn(email: String, password: String) {
        store.save(api.signIn(email.trim(), password))
    }

    suspend fun signOut() = store.clear()

    /**
     * A token that is valid now, or null when nobody is signed in (or the server ended the session).
     * With [force] the token is renewed even if it looks fresh; used after the server answered 401.
     */
    suspend fun accessToken(force: Boolean = false): String? = lock.withLock {
        val current = store.current() ?: return@withLock null
        if (!force && !SessionPolicy.needsRefresh(current, clock())) return@withLock current.accessToken
        try {
            val renewed = api.refresh(current.refreshToken)
            store.save(renewed)
            renewed.accessToken
        } catch (e: AuthException) {
            when {
                // The server ended the session: the user must sign in again.
                e.kind == AuthException.Kind.SESSION_EXPIRED -> {
                    store.clear()
                    null
                }
                // Offline: keep the session, and use the old token while it is still accepted.
                !force && current.expiresAtEpochSec > clock() -> current.accessToken
                else -> throw e
            }
        }
    }
}
