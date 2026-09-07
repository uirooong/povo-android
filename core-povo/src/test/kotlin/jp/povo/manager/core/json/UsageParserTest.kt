package jp.povo.manager.core.json

import jp.povo.manager.core.model.DataBucketKind
import jp.povo.manager.core.model.PlanUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser tests run against responses actually captured from the live service,
 * not hand-written examples — the whole point is to pin down a payload nobody
 * documented.
 */
class UsageParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/samples/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    @Test
    fun `parses an account whose allowances are all empty`() {
        val usage = requireNotNull(UsageParser.parse(fixture("account-usage-plan-get.json")))

        assertEquals(5, usage.buckets.size)
        assertEquals(0.0, usage.totalLeftKb, 0.0)
        assertTrue("no bucket should be active", usage.activeBuckets.isEmpty())
    }

    @Test
    fun `parses a contract whose data sits in boost rather than basic`() {
        val usage = requireNotNull(UsageParser.parse(fixture("account-usage-plan-get.plan-account.json")))

        // The contract's data is entirely in `boost`. Reading `basic` alone —
        // the obvious implementation — would report zero remaining on an
        // account with 55 GB left.
        assertEquals(0.0, usage[DataBucketKind.BASIC]!!.leftKb, 0.0)

        val boost = requireNotNull(usage[DataBucketKind.BOOST])
        assertEquals(62914560.37109375, boost.leftKb, 0.000001)
        assertEquals(62914560.62890625, boost.usedKb, 0.000001)

        assertEquals(listOf(DataBucketKind.BOOST), usage.activeBuckets.map { it.kind })
        assertEquals(62914560.37109375, usage.totalLeftKb, 0.000001)
    }

    @Test
    fun `sizes are binary kilobytes so a 120 GB contract reads back as 120 GB`() {
        val usage = UsageParser.parse(fixture("account-usage-plan-get.plan-account.json"))!!
        val boost = usage[DataBucketKind.BOOST]!!

        val totalKb = boost.usedKb + boost.leftKb
        assertEquals(125_829_121.0, totalKb, 0.5)
        assertEquals("120.00 GB", PlanUsage.formatKb(totalKb))
    }

    @Test
    fun `plan_kb stays null on the buckets that omit it`() {
        val usage = UsageParser.parse(fixture("account-usage-plan-get.plan-account.json"))!!

        // `bonus` and `boost` genuinely have no `plan_kb`. Defaulting it to 0.0
        // would claim a ceiling of zero on a bucket holding 55 GB.
        assertNull(usage[DataBucketKind.BONUS]!!.planKb)
        assertNull(usage[DataBucketKind.BOOST]!!.planKb)
        assertEquals(0.0, usage[DataBucketKind.BASIC]!!.planKb!!, 0.0)
    }

    @Test
    fun `boost reports its purchase step as one binary gigabyte`() {
        val usage = UsageParser.parse(fixture("account-usage-plan-get.plan-account.json"))!!
        assertEquals(1_048_576.0, usage[DataBucketKind.BOOST]!!.sectionMinValueKb!!, 0.0)
    }

    @Test
    fun `formats sizes with binary divisors`() {
        assertEquals("512 KB", PlanUsage.formatKb(512.0))
        assertEquals("1.0 MB", PlanUsage.formatKb(1024.0))
        assertEquals("1.00 GB", PlanUsage.formatKb(1024.0 * 1024.0))
        assertEquals("0 KB", PlanUsage.formatKb(0.0))
    }

    @Test
    fun `returns null rather than an empty reading when the payload is unusable`() {
        assertNull(UsageParser.parse(""))
        assertNull(UsageParser.parse("not json"))
        assertNull(UsageParser.parse("""{"unexpected":true}"""))
    }
}
