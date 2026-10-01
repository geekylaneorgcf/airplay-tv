package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilitySetupTest {

    private val pkg = "io.github.besliky.airplaytv"
    private val sleep = AccessibilitySetup.component(pkg, "$pkg.service.SleepService")
    private val menu = AccessibilitySetup.component(pkg, "$pkg.service.MenuKeyService")

    @Test
    fun `the service name is written in the short form`() {
        assertEquals("io.github.besliky.airplaytv/.service.SleepService", sleep)
        assertEquals("a.b/c.d.E", AccessibilitySetup.component("a.b", "c.d.E"))
    }

    @Test
    fun `with nothing on, the list is just the service`() {
        assertEquals(menu, AccessibilitySetup.withService(null, menu))
        assertEquals(menu, AccessibilitySetup.withService("", menu))
        assertEquals(menu, AccessibilitySetup.withService("  :  ", menu))
    }

    @Test
    fun `services that are already on are kept, in the order they were`() {
        assertEquals("other.app/.Svc:$menu", AccessibilitySetup.withService("other.app/.Svc", menu))
        assertEquals("$sleep:$menu", AccessibilitySetup.withService(sleep, menu))
        assertEquals("a/.A:b/.B:$menu", AccessibilitySetup.withService("a/.A:b/.B", menu))
    }

    @Test
    fun `a service that is already on is not added twice, in either spelling`() {
        assertEquals(menu, AccessibilitySetup.withService(menu, menu))
        val full = "$pkg/$pkg.service.MenuKeyService"
        assertEquals(full, AccessibilitySetup.withService(full, menu))
        assertEquals("$sleep:$full", AccessibilitySetup.withService("$sleep:$full", menu))
    }
}
