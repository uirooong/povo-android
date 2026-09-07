package jp.povo.manager.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are inline rather than captured: this endpoint's payload is the one
 * that carries the contractor's name and email, so a real capture is exactly
 * the sort of thing that should not sit in a repository.
 */
class ProfileParserTest {

    @Test
    fun `reads the activation date and drops the time`() {
        // The timestamp is UTC midnight, so converting it to JST would move the
        // date forward a day and show the wrong 開通日.
        assertEquals("2024-01-01", ProfileParser.activationDate(WRAPPED))
    }

    @Test
    fun `reads a bare payload as well as a wrapped one`() {
        assertEquals("2023-06-15", ProfileParser.activationDate(BARE))
        assertEquals("契約者 太郎", ProfileParser.customerName(BARE))
    }

    @Test
    fun `falls back to the initial activation date`() {
        // Only a fallback: initial_activation_date is the account's first line
        // ever, which differs from this line's once a line is re-issued.
        assertEquals("2020-03-03", ProfileParser.activationDate(INITIAL_ONLY))
    }

    @Test
    fun `returns null rather than throwing on payloads without the field`() {
        assertNull(ProfileParser.activationDate("""{"telco_info":{"phone_no":"09012345678"}}"""))
        assertNull(ProfileParser.activationDate("""{"result":{}}"""))
        assertNull(ProfileParser.activationDate("not json at all"))
        assertNull(ProfileParser.activationDate(""))
        // Present but empty is the same as absent, not an empty date.
        assertNull(ProfileParser.activationDate("""{"telco_info":{"activation_date":""}}"""))
    }

    private companion object {
        const val WRAPPED = """
            {"result":{"external_id":"ext-0000","email":"example.user@example.com",
             "telco_info":{"service_instance_no":"LW000000000","customer_name":"契約者 太郎",
             "phone_no":"09012345678","activation_date":"2024-01-01T00:00:00.000Z",
             "initial_activation_date":"2024-01-01T00:00:00.000Z"}}}
        """

        const val BARE = """
            {"external_id":"ext-0000",
             "telco_info":{"customer_name":"契約者 太郎","activation_date":"2023-06-15T00:00:00.000Z"}}
        """

        const val INITIAL_ONLY = """
            {"telco_info":{"initial_activation_date":"2020-03-03T00:00:00.000Z"}}
        """
    }
}
