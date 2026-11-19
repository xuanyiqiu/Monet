package dev.monet.app.core.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private object AuthKeys {
    val access = stringPreferencesKey("access_token")
    val refresh = stringPreferencesKey("refresh_token")
    val expiresAt = longPreferencesKey("expires_at")
}

private val Context.authDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth")

/** Pure mapping so it can be unit tested without Android. */
internal fun Preferences.toSession(): Session? {
    val access = this[AuthKeys.access]
    val refresh = this[AuthKeys.refresh]
    if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) return null
    return Session(access, refresh, this[AuthKeys.expiresAt] ?: 0L)
}

/**
 * Keeps the session in the app's private storage (sandboxed per app on Android), separate from the
 * settings file so signing out can wipe it without touching preferences.
 */
class SessionStore(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.authDataStore)

    val session: Flow<Session?> = store.data.map { it.toSession() }

    suspend fun current(): Session? = session.first()

    suspend fun save(session: Session) {
        store.edit {
            it[AuthKeys.access] = session.accessToken
            it[AuthKeys.refresh] = session.refreshToken
            it[AuthKeys.expiresAt] = session.expiresAtEpochSec
        }
    }

    suspend fun clear() {
        store.edit { it.clear() }
    }
}
