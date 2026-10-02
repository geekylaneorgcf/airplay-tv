package io.github.besliky.airplaytv.service

import io.github.besliky.airplaytv.service.SelfCheck.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckTest {

    private fun healthy() = SelfCheck.Inputs(
        enabled = true, status = ReceiverState.Status.READY, port = 7000, portOpen = true, publishedName = "AirPlay TV",
        hasNetwork = true, wifi = true, wifiMhz = 5180, vpnActive = false,
    )

    private fun levels(i: SelfCheck.Inputs) = SelfCheck.evaluate(i).map { it.level }

    @Test
    fun `a healthy receiver has nothing to complain about`() {
        val findings = SelfCheck.evaluate(healthy())
        assertTrue(findings.all { it.level == Level.OK })
        assertTrue(findings.any { it.text.contains("AirPlay TV") })
    }

    @Test
    fun `switched off, or off the network, stops at that`() {
        assertEquals(listOf(Level.FAIL), levels(healthy().copy(enabled = false)))
        assertEquals(listOf(Level.FAIL), levels(healthy().copy(hasNetwork = false)))
    }

    @Test
    fun `a port nobody answers on is a failure and says so`() {
        val findings = SelfCheck.evaluate(healthy().copy(portOpen = false))
        assertEquals(Level.FAIL, findings.first().level)
        assertTrue(findings.first().text.contains("7000"))
    }

    @Test
    fun `a name that is not announced is a warning`() {
        assertTrue(SelfCheck.evaluate(healthy().copy(publishedName = "")).any { it.level == Level.WARN && it.text.contains("not announced") })
    }

    @Test
    fun `the 2 point 4 band and a VPN are warnings`() {
        assertTrue(SelfCheck.evaluate(healthy().copy(wifiMhz = 2437)).any { it.level == Level.WARN && it.text.contains("2.4 GHz") })
        assertTrue(SelfCheck.evaluate(healthy().copy(vpnActive = true)).any { it.level == Level.WARN && it.text.contains("VPN") })
        assertTrue(SelfCheck.evaluate(healthy().copy(wifi = false)).any { it.text.contains("cable") })
    }

    @Test
    fun `the findings read one to a paragraph with a mark`() {
        val text = SelfCheck.text(listOf(SelfCheck.Finding(Level.OK, "a"), SelfCheck.Finding(Level.FAIL, "b")))
        assertEquals("✓  a\n\n✗  b", text)
    }
}
