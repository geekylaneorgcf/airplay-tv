package io.github.besliky.airplaytv.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuScopeTest {

    @Test
    fun `the system settings keep their Menu button`() {
        assertTrue(MenuScope.leaveToApp("com.amazon.tv.settings.v2"))
        assertTrue(MenuScope.leaveToApp("com.android.settings"))
    }

    @Test
    fun `other apps and the unknown still give the button to the TV`() {
        assertFalse(MenuScope.leaveToApp("com.google.android.youtube.tv"))
        assertFalse(MenuScope.leaveToApp("io.github.geekylaneorgcf.tvhome"))
        assertFalse(MenuScope.leaveToApp(null))
    }
}
