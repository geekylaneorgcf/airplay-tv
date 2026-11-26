package io.github.besliky.airplaytv.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorScaleTest {

    @Test
    fun gainRunsFromSilenceToUntouched() {
        assertEquals(MonitorScale.FLOOR_DB, MonitorScale.gainDb(0, 15, false), 0f)
        assertEquals(0f, MonitorScale.gainDb(15, 15, false), 0f)
        assertEquals(-3f, MonitorScale.gainDb(14, 15, false), 0f)
        assertEquals(-42f, MonitorScale.gainDb(1, 15, false), 0f)
        assertEquals(MonitorScale.FLOOR_DB, MonitorScale.gainDb(15, 15, true), 0f)
    }

    @Test
    fun shadeAlphas() {
        assertEquals(0, MonitorScale.dimAlpha(10))
        assertEquals(217, MonitorScale.dimAlpha(0))
        assertEquals(0, MonitorScale.warmAlpha(0))
        assertEquals(77, MonitorScale.warmAlpha(5))
    }

    @Test
    fun sessionsFromTheFlingerDump() {
        val dump = """
            Global session refs:
              session   pid count
                  337 17463     1
                  345 999     1
            Hardware status: 0
            Output thread 0xa80038c0, name AudioOut_D, tid 400, type 0 (MIXER):
              1 Tracks of which 1 are active
                T Name Active Client Session S  Flags   Format Chn mask  SRate ST  L dB  R dB  VS dB
                     0    yes  17463     337 A  0x000 00000005 00000003  48000  3     0     0     0
                     1     no    999     401 I  0x000 00000005 00000003  48000  3     0     0     0
        """.trimIndent().lineSequence()
        // 345 is only held by this app's own effect (pid 999), 401 is this app's own track
        assertEquals(setOf(337, 401), MonitorScale.sessions(dump, 999))
        assertNull(MonitorScale.sessions(sequenceOf("Permission Denial: can't dump"), 1))
    }
}
