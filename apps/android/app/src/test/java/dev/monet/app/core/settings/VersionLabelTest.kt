package dev.monet.app.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLabelTest {

    @Test
    fun `a release version name and code are shown together`() {
        assertEquals("0.2.0 (15)", formatVersion("0.2.0", 15))
    }

    @Test
    fun `a version name derived from git describe is kept as is`() {
        assertEquals("0.2.0-1-g15d183d (71)", formatVersion("0.2.0-1-g15d183d", 71))
    }

    @Test
    fun `a blank or missing version name falls back to dev`() {
        assertEquals("dev (7)", formatVersion("", 7))
        assertEquals("dev (7)", formatVersion("   ", 7))
    }
}
