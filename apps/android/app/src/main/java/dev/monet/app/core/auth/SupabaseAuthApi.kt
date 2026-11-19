package dev.monet.app.core.auth

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class AuthException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** Wrong email or password. */
        INVALID_CREDENTIALS,

        /** The refresh token was refused: the user has to sign in again. */
        SESSION_EXPIRED,

        /** No connection, or the server could not be reached. */
        NETWORK,
        OTHER,
    }
}

@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

@Serializable
internal data class AuthErrorBody(
    val msg: String? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
)

private val authJson = Json { ignoreUnknownKeys = true }

/** Maps Supabase's token response to a [Session]. Pure, so it is unit tested. */
internal fun TokenResponse.toSession(nowEpochSec: Long): Session =
    Session(accessToken, refreshToken, expiresAt ?: (nowEpochSec + expiresIn))

/**
 * Talks to Supabase Auth over REST (no SDK). Uses the publishable key, which is meant to ship inside
 * the app; it only identifies the project and grants nothing on its own.
 */
class SupabaseAuthApi(
    baseUrl: String,
    private val publishableKey: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val base = baseUrl.trimEnd('/')

    suspend fun signIn(email: String, password: String): Session =
        token("password", buildJsonObject { put("email", email); put("password", password) }.toString())

    suspend fun refresh(refreshToken: String): Session =
        token("refresh_token", buildJsonObject { put("refresh_token", refreshToken) }.toString())

    private suspend fun token(grantType: String, body: String): Session = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/auth/v1/token?grant_type=$grantType")
            .header("apikey", publishableKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    authJson.decodeFromString(TokenResponse.serializer(), text).toSession(clock())
                } else {
                    throw errorFor(grantType, response.code, text)
                }
            }
        } catch (e: IOException) {
            throw AuthException(AuthException.Kind.NETWORK, "Could not reach the server", e)
        }
    }

    private fun errorFor(grantType: String, status: Int, body: String): AuthException {
        val parsed = runCatching { authJson.decodeFromString(AuthErrorBody.serializer(), body) }.getOrNull()
        val detail = parsed?.errorDescription ?: parsed?.msg ?: parsed?.message ?: "Sign-in failed ($status)"
        val kind = when {
            // 400/401/403 on a refresh means the refresh token is no longer valid.
            grantType == "refresh_token" && status in 400..403 -> AuthException.Kind.SESSION_EXPIRED
            grantType == "password" && status in 400..401 -> AuthException.Kind.INVALID_CREDENTIALS
            status >= 500 -> AuthException.Kind.NETWORK
            else -> AuthException.Kind.OTHER
        }
        return AuthException(kind, detail)
    }
}
