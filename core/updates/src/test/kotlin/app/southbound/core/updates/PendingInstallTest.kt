package app.southbound.core.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInstallTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Test
    fun `an update waiting for its confirmation is not downloaded again each round`() {
        assertFalse(PendingInstall.shouldDownload("0.1.60", t0, "0.1.60", t0 + day, asked = false))
        assertFalse(PendingInstall.shouldDownload("0.1.60", t0, "0.1.60", t0 + PendingInstall.MAX_WAIT_MILLIS - 1, asked = false))
    }

    @Test
    fun `it is offered again when asked, when a newer one comes, or after the wait`() {
        assertTrue(PendingInstall.shouldDownload("0.1.60", t0, "0.1.60", t0 + day, asked = true))
        assertTrue(PendingInstall.shouldDownload("0.1.60", t0, "0.1.61", t0 + day, asked = false))
        assertTrue(PendingInstall.shouldDownload("0.1.60", t0, "0.1.60", t0 + PendingInstall.MAX_WAIT_MILLIS, asked = false))
        assertTrue(PendingInstall.shouldDownload(null, 0, "0.1.60", t0, asked = false))
        // A clock set back is no reason to wait.
        assertTrue(PendingInstall.shouldDownload("0.1.60", t0, "0.1.60", t0 - 1, asked = false))
    }
}
