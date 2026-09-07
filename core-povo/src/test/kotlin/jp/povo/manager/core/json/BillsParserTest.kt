package jp.povo.manager.core.json

import jp.povo.manager.core.model.BillStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillsParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/samples/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    private val document by lazy {
        requireNotNull(BillsParser.parse(fixture("layout-bills-info.json")))
    }

    @Test
    fun `finds the invoices povo-core's typed accessor misses`() {
        // povo-core's get_bills_info() returns 0 entries for this exact payload
        // because it looks for a top-level `bills` array. They are really under
        // past_bills.list[].
        assertEquals(6, document.past.size)
    }

    @Test
    fun `reads a past invoice`() {
        val bill = document.past.first()

        assertEquals("REG0000000000000", bill.billId)
        assertEquals("2026年7月 - 2026年8月", bill.title)
        assertEquals(BillStatus.PAID, bill.status)
        assertEquals(180.0, bill.amount!!.value, 0.0)
        assertTrue("invoice should advertise a PDF", bill.hasPdf)
    }

    @Test
    fun `takes the status from the payload instead of inferring it`() {
        // Prior analysis of another client concluded there was no status field
        // and that it had to be inferred from which section a row came from.
        // The payload does carry `type`, so it is read directly.
        assertTrue(document.past.all { it.status == BillStatus.PAID })
    }

    @Test
    fun `treats the timestamp as epoch milliseconds`() {
        val time = requireNotNull(document.past.first().timeEpochMillis)
        assertEquals(1785509999000L, time)
        // Read as seconds this would land in the year 58,532.
        assertTrue("should be a plausible date", time in 1_500_000_000_000L..2_000_000_000_000L)
    }

    @Test
    fun `keeps the currency symbol the payload actually sent`() {
        val amount = document.past.first().amount!!
        assertEquals("円", amount.prefix)
        assertEquals("JPY", amount.postfix)
        // Both decorations ship together; rendering both would give "180円JPY".
        // `円` follows the amount in Japanese despite the field being named
        // "prefix" — see MoneyTest.
        assertEquals("180円", amount.format())
    }

    @Test
    fun `reads upcoming estimates, which carry no invoice id`() {
        assertEquals(3, document.upcoming.size)
        val first = document.upcoming.first()
        assertEquals(BillStatus.UPCOMING, first.status)
        assertNull("an unissued estimate has no PDF to download", first.billId)
        assertFalse(first.hasPdf)
        requireNotNull(first.title)
    }

    @Test
    fun `reads the instant-charges total`() {
        assertEquals(0.0, document.instantChargesPaid!!.value, 0.0)
        assertTrue(document.instantCharges.isEmpty())
    }

    @Test
    fun `parses the second account's document too`() {
        val other = requireNotNull(
            BillsParser.parse(fixture("layout-bills-info.plan-account.json")))
        assertTrue("expected some billing rows", other.all.isNotEmpty())
        assertTrue(other.past.all { it.billId != null })
    }

    @Test
    fun `orders the combined timeline newest first`() {
        val times = document.all.mapNotNull { it.timeEpochMillis }
        assertEquals(times.sortedDescending(), times)
    }

    @Test
    fun `returns null on an unusable payload`() {
        assertNull(BillsParser.parse(""))
        assertNull(BillsParser.parse("not json"))
        assertNull(BillsParser.parse("""{"unexpected":true}"""))
    }
}
