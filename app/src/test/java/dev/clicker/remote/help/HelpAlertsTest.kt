package dev.clicker.remote.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpAlertsTest {
    @Test fun urgentRequestsAreMarkedWithABang() {
        assertTrue(HelpAlerts.isUrgent("!Air machine"))
        assertTrue(HelpAlerts.isUrgent("  ! Air machine"))
        assertFalse(HelpAlerts.isUrgent("Water please"))
        assertEquals("Air machine", HelpAlerts.label(" ! Air machine"))
        assertEquals("Water please", HelpAlerts.label("Water please"))
    }
}
