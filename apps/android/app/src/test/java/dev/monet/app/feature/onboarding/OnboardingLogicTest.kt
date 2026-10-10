package dev.monet.app.feature.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingLogicTest {
    @Test
    fun `there are at most four steps and they walk forward and back`() {
        assertEquals(4, OnboardingStep.entries.size)
        var s = OnboardingStep.LANGUAGE
        assertTrue(s.isFirst)
        s = s.next().next().next()
        assertEquals(OnboardingStep.TRY_IT, s)
        assertTrue(s.isLast)
        assertEquals(OnboardingStep.TRY_IT, s.next())
        assertEquals(OnboardingStep.CAPTURE, s.previous())
        assertEquals(OnboardingStep.LANGUAGE, OnboardingStep.LANGUAGE.previous())
    }

    @Test
    fun `demo starts and ends clear so the loop restarts cleanly`() {
        assertEquals(DemoFrame(0f, 0f), demoFrame(0f))
        assertEquals(DemoFrame(0f, 0f), demoFrame(1f))
    }

    @Test
    fun `the band appears before the chip and both hold in the middle`() {
        val early = demoFrame(0.3f)
        assertTrue(early.bandAlpha > 0f)
        assertEquals(0f, early.chipAlpha, 0f)
        val mid = demoFrame(0.6f)
        assertEquals(1f, mid.bandAlpha, 0f)
        assertEquals(1f, mid.chipAlpha, 0f)
    }

    @Test
    fun `both fade out together at the end`() {
        val late = demoFrame(0.94f)
        assertTrue(late.bandAlpha in 0.01f..0.99f)
        assertTrue(late.chipAlpha in 0.01f..0.99f)
        assertEquals(late.bandAlpha, late.chipAlpha, 0.0001f)
    }

    @Test
    fun `phase is clamped and alphas stay in range`() {
        assertEquals(demoFrame(0f), demoFrame(-3f))
        assertEquals(demoFrame(1f), demoFrame(9f))
        (0..100).forEach {
            val f = demoFrame(it / 100f)
            assertTrue(f.bandAlpha in 0f..1f && f.chipAlpha in 0f..1f)
        }
    }

    @Test
    fun `reduced motion shows the finished state`() {
        assertEquals(1f, StaticDemoFrame.bandAlpha, 0f)
        assertEquals(1f, StaticDemoFrame.chipAlpha, 0f)
    }

    @Test
    fun `try it offers memes and idioms in every language`() {
        assertTrue(TrySamples.size >= 4)
        assertTrue(TrySamples.any { it.any { c -> c.code in 0x4E00..0x9FFF } })
        assertTrue(TrySamples.any { it.contains("está") })
        assertFalse(TrySamples.any { it.isBlank() })
    }
}
