package dev.monet.app.core.auth

/**
 * A signed-in user. The access token is short-lived (about an hour); the refresh token stays valid
 * until the user signs out, so the app can keep renewing the access token without asking for the
 * password again.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Unix time, in seconds, at which [accessToken] stops being accepted. */
    val expiresAtEpochSec: Long,
)

/** What the UI needs to decide which screen to show. */
sealed interface AuthState {
    /** The stored session has not been read yet. */
    data object Loading : AuthState
    data object SignedOut : AuthState
    data object SignedIn : AuthState
}

object SessionPolicy {
    /**
     * Should the access token be renewed before it is used for a request?
     *
     * Renewing too late means a request goes out with a token the server rejects. Renewing too early
     * wastes a network call on every request. [nowEpochSec] is passed in (not read from the clock) so
     * this stays a pure function that unit tests can drive.
     */
    fun needsRefresh(session: Session, nowEpochSec: Long): Boolean {
        // TODO(human): decide when a token counts as "about to expire" (think of a safety margin in
        // seconds, because the request still has to travel to the server). Returning true always
        // works but renews on every call; replace it with a comparison against session.expiresAtEpochSec.
        return true
    }
}
