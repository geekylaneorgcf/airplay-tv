package io.github.besliky.airplaytv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewTest {

    @Test
    fun `the notes are the lines that are not empty`() {
        assertEquals(listOf("One.", "Two."), WhatsNew.parse("  One.\n\n Two.  \n"))
        assertNull(WhatsNew.parse("  \n\n"))
        assertNull(WhatsNew.parse(null))
    }

    @Test
    fun `a version shows its notes once`() {
        val notes = listOf("One.")
        assertTrue(WhatsNew.shouldShow("", "0.2.1-rc.12", notes))
        assertTrue(WhatsNew.shouldShow("0.2.1-rc.11", "0.2.1-rc.12", notes))
        assertFalse(WhatsNew.shouldShow("0.2.1-rc.12", "0.2.1-rc.12", notes))
        assertFalse(WhatsNew.shouldShow("", "0.2.1-rc.12", null))
    }
}
