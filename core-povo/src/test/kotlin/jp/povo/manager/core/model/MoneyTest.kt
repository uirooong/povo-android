package jp.povo.manager.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyTest {

    @Test
    fun `puts the yen word after the amount even though the API calls it a prefix`() {
        // The billing endpoints send {"prefix":"円","postfix":"JPY","value":180}.
        // Trusting the field name gives "円180", which no Japanese reader wants.
        assertEquals("180円", Money(value = 180.0, prefix = "円", postfix = "JPY").format())
    }

    @Test
    fun `puts the yen sign before the amount`() {
        // The usage endpoint uses the sign rather than the word for the same currency.
        assertEquals("¥3.3", Money(value = 3.3, prefix = "¥").format())
    }

    @Test
    fun `drops a trailing zero on whole amounts`() {
        // Values routinely arrive as 180.0; "180.0円" reads like a rounding artefact.
        assertEquals("180円", Money(value = 180.0, prefix = "円").format())
        assertEquals("0円", Money(value = 0.0, prefix = "円").format())
    }

    @Test
    fun `groups thousands the way the service does`() {
        // The order-history page renders this exact amount as "12,345円".
        assertEquals("12,345円", Money(value = 12345.0, prefix = "円").format())
        assertEquals("1,234,567円", Money(value = 1234567.0, prefix = "円").format())
    }

    @Test
    fun `keeps a fractional amount`() {
        assertEquals("3.3円", Money(value = 3.3, prefix = "円").format())
    }

    @Test
    fun `falls back to yen when the payload carries no symbol`() {
        assertEquals("500円", Money(value = 500.0).format())
        // JPY alone is a currency code, not something to render next to a number.
        assertEquals("500円", Money(value = 500.0, postfix = "JPY").format())
    }

    @Test
    fun `uses the postfix when that is the only decoration`() {
        assertEquals("500pt", Money(value = 500.0, postfix = "pt").format())
    }
}
