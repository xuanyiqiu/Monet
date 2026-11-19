package dev.monet.app.feature.auth

import dev.monet.app.core.auth.AuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewModelTest {
    @Test
    fun `submit needs an email, a password, and no request in flight`() {
        assertFalse(LoginUiState().canSubmit)
        assertFalse(LoginUiState(email = "a@b.c").canSubmit)
        assertFalse(LoginUiState(password = "pw").canSubmit)
        assertFalse(LoginUiState(email = "  ", password = "pw").canSubmit)
        assertTrue(LoginUiState(email = "a@b.c", password = "pw").canSubmit)
        assertFalse(LoginUiState(email = "a@b.c", password = "pw", busy = true).canSubmit)
    }

    @Test
    fun `every failure kind has a friendly message and none leaks server text`() {
        val raw = "raw server detail"
        AuthException.Kind.entries.forEach { kind ->
            val text = loginMessage(AuthException(kind, raw))
            assertTrue(text.isNotBlank())
            assertFalse(text.contains(raw))
        }
        assertEquals("Wrong email or password.", loginMessage(AuthException(AuthException.Kind.INVALID_CREDENTIALS, "x")))
    }
}
