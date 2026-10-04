package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAppsTest {

    @Test
    fun `an app that is used again moves to the front and is not listed twice`() {
        assertEquals(listOf("b", "a", "c"), RecentApps.add(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("d", "a", "b"), RecentApps.add(listOf("a", "b"), "d"))
    }

    @Test
    fun `the list is cut to five`() {
        val full = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("f", "a", "b", "c", "d"), RecentApps.add(full, "f"))
    }

    @Test
    fun `the system's windows and this app's own do not count`() {
        assertFalse(RecentApps.counts("com.android.systemui", "io.github.besliky.airplaytv"))
        assertFalse(RecentApps.counts("io.github.geekylaneorgcf.tvhome", "io.github.besliky.airplaytv"))
        assertFalse(RecentApps.counts("io.github.besliky.airplaytv", "io.github.besliky.airplaytv"))
        assertFalse(RecentApps.counts("", "x"))
        assertFalse(RecentApps.counts(null, "x"))
        assertTrue(RecentApps.counts("com.amazon.firetv.youtube", "io.github.besliky.airplaytv"))
    }

    @Test
    fun `a stored list survives and junk does not hurt`() {
        val list = listOf("a.b", "c.d")
        assertEquals(list, RecentApps.parse(RecentApps.encode(list)))
        assertEquals(emptyList<String>(), RecentApps.parse(""))
        assertEquals(listOf("a"), RecentApps.parse(" a  a "))
    }

    @Test
    fun `the switcher leaves out the app in front and what cannot be started`() {
        val list = listOf("front", "one", "gone", "two")
        assertEquals(listOf("one", "two"), RecentApps.choices(list, "front") { it != "gone" })
    }
}
