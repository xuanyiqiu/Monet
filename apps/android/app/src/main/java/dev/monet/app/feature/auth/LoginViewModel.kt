package dev.monet.app.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.monet.app.core.auth.AuthException
import dev.monet.app.core.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotEmpty() && !busy
}

/** User-facing text for a failed sign-in. Never echoes the server's raw message. */
internal fun loginMessage(e: AuthException): String = when (e.kind) {
    AuthException.Kind.INVALID_CREDENTIALS -> "Wrong email or password."
    AuthException.Kind.NETWORK -> "Can't reach Monet. Check your connection and try again."
    AuthException.Kind.SESSION_EXPIRED -> "Your session ended. Please sign in again."
    AuthException.Kind.OTHER -> "Couldn't sign in. Please try again."
}

class LoginViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmail(value: String) = _state.update { it.copy(email = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                auth.signIn(s.email, s.password)
                // On success the session flow flips to SignedIn and the screen is replaced.
                _state.update { it.copy(busy = false, password = "") }
            } catch (e: AuthException) {
                _state.update { it.copy(busy = false, error = loginMessage(e)) }
            }
        }
    }
}
