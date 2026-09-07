package jp.povo.manager.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the billing throttle.
 *
 * The periodic refresh runs every 15 minutes for the data allowance, but the
 * billing timeline and purchase history are two extra requests per account
 * against someone else's production service and change monthly at most. Getting
 * this wrong is invisible in the UI and only shows up as request volume, so the
 * boundaries are asserted rather than eyeballed.
 */
class BillsPolicyTest {

    @Test
    fun `a user-triggered refresh is never throttled`() {
        assertTrue(BillsPolicy.ALWAYS.wants(null))
        assertTrue(BillsPolicy.ALWAYS.wants(System.currentTimeMillis()))
    }

    @Test
    fun `an account that has never fetched bills fetches them`() {
        assertTrue(
            "a null timestamp must not be read as 'fetched just now'",
            BillsPolicy.IF_STALE.wants(null),
        )
    }

    @Test
    fun `a recent fetch is skipped`() {
        assertFalse(BillsPolicy.IF_STALE.wants(System.currentTimeMillis()))
        // The three runs that follow a fetch, at 15-minute spacing.
        assertFalse(BillsPolicy.IF_STALE.wants(minutesAgo(15)))
        assertFalse(BillsPolicy.IF_STALE.wants(minutesAgo(30)))
        assertFalse(BillsPolicy.IF_STALE.wants(minutesAgo(45)))
    }

    @Test
    fun `the fourth run clears the threshold`() {
        // 55 minutes, not 60: at a 15-minute cadence the fourth run lands at
        // ~60 minutes, and a 60-minute threshold would miss it on any jitter and
        // slip billing to every 75 minutes instead.
        assertTrue(BillsPolicy.IF_STALE.wants(minutesAgo(60)))
        assertTrue(BillsPolicy.IF_STALE.wants(minutesAgo(55)))
        assertFalse(BillsPolicy.IF_STALE.wants(minutesAgo(54)))
    }

    private fun minutesAgo(minutes: Long) =
        System.currentTimeMillis() - minutes * 60 * 1000L
}
