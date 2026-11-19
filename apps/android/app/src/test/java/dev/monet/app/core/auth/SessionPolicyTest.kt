package dev.monet.app.core.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPolicyTest {
    private val session = Session("a", "r", expiresAtEpochSec = 10_000)

    @Test
    fun `a token with plenty of time left is reused`() {
        assertFalse(SessionPolicy.needsRefresh(session, nowEpochSec = 1_000))
        assertFalse(SessionPolicy.needsRefresh(session, nowEpochSec = 10_000 - SessionPolicy.REFRESH_MARGIN_SEC - 1))
    }

    @Test
    fun `a token inside the safety margin is renewed`() {
        assertTrue(SessionPolicy.needsRefresh(session, nowEpochSec = 10_000 - SessionPolicy.REFRESH_MARGIN_SEC))
        assertTrue(SessionPolicy.needsRefresh(session, nowEpochSec = 9_990))
    }

    @Test
    fun `an expired token is renewed`() {
        assertTrue(SessionPolicy.needsRefresh(session, nowEpochSec = 10_000))
        assertTrue(SessionPolicy.needsRefresh(session, nowEpochSec = 50_000))
    }

    @Test
    fun `the margin is small compared with a one-hour token`() {
        assertEquals(60L, SessionPolicy.REFRESH_MARGIN_SEC)
    }
}
