package jp.povo.manager.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Pins the 180-day arithmetic against povo's own published examples.
 *
 * Everything the feature shows rests on these few days of offset, and being one
 * day out is invisible on screen — it looks like a perfectly plausible date.
 * The two anchors below come from povo rather than from reasoning.
 */
class SuspensionTest {

    @Test
    fun `expiry matches the FAQ's own worked example`() {
        // faq.povo.jp No.896: a 30-day topping bought 1 January expires on the
        // 31st. So the purchase day is not counted as day one.
        assertEquals("2026-01-31", Suspension.expiryFrom("2026-01-01", 30))
    }

    @Test
    fun `expiry matches a real 365-day topping`() {
        // Observed on a live account: bought 2026-02-28, the plan page reports
        // 2027年 2月 28日. 2026 is not a leap year, so this also pins that the
        // arithmetic is calendar-based rather than 365 * 86400 seconds.
        assertEquals("2027-02-28", Suspension.expiryFrom("2026-02-28", 365))
        // 365 days over a window that does contain a leap day stops a calendar
        // day short of the anniversary — the arithmetic follows the calendar
        // rather than adding a fixed number of seconds.
        assertEquals("2028-05-31", Suspension.expiryFrom("2027-06-01", 365))
    }

    @Test
    fun `counting starts the day after expiry, per the FAQ`() {
        // "有効期限の翌日から180日間" — the window opens 1 February and its last
        // day is 30 July, so suspension can follow from 31 July.
        val forecast = requireNotNull(Suspension.forecast("2026-01-31", today = LocalDate.of(2026, 1, 31)))

        assertEquals(LocalDate.of(2026, 7, 31), forecast.suspendsOn)
        assertEquals(181, forecast.daysLeft)
        // Suspension is not the end: povo closes the contract 30 days later.
        assertEquals(LocalDate.of(2026, 8, 30), forecast.terminatesOn)
    }

    @Test
    fun `days left counts calendar days across the boundary`() {
        val expiry = "2026-01-31"
        fun daysLeftOn(y: Int, m: Int, d: Int) =
            requireNotNull(Suspension.forecast(expiry, LocalDate.of(y, m, d))).daysLeft

        assertEquals(1, daysLeftOn(2026, 7, 30))
        assertEquals(0, daysLeftOn(2026, 7, 31))
        assertEquals(-1, daysLeftOn(2026, 8, 1))
    }

    @Test
    fun `a passed date reads as overdue rather than as zero`() {
        // povo suspends lines in batches ("順次"), so a line can outlive its
        // date. Collapsing this into 0 would hide the difference between "today
        // is the day" and "you are a month past it".
        val today = requireNotNull(Suspension.forecast("2026-01-31", LocalDate.of(2026, 7, 31)))
        val past = requireNotNull(Suspension.forecast("2026-01-31", LocalDate.of(2026, 8, 30)))

        assertFalse(today.overdue)
        assertTrue(past.overdue)
        assertEquals(-30, past.daysLeft)
    }

    @Test
    fun `a value that is not a date is a null, not a crash`() {
        // These come from stored user input, so a hand-edited or half-migrated
        // value must degrade to "not set" rather than take the screen down.
        listOf("", "   ", "2026/02/28", "2026-13-01", "2026-02-30", "yesterday").forEach {
            assertNull("expected null for '$it'", Suspension.forecast(it, LocalDate.of(2026, 1, 1)))
            assertNull("expected null for '$it'", Suspension.expiryFrom(it, 30))
        }
        assertNull(Suspension.parseDate(null))
        // A duration has to be at least a day for an expiry to mean anything.
        assertNull(Suspension.expiryFrom("2026-02-28", 0))
        assertNull(Suspension.expiryFrom("2026-02-28", -1))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(LocalDate.of(2026, 2, 28), Suspension.parseDate("  2026-02-28 "))
    }
}
