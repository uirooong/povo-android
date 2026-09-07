package jp.povo.manager.core.model

import kotlinx.serialization.Serializable
import java.text.DecimalFormat
import java.util.Locale

/**
 * A monetary amount as the API expresses it.
 *
 * Prices are never bare numbers on the wire — they always arrive as an object
 * carrying its own decoration, and the decoration is not consistent: the
 * billing endpoints use `prefix: "円"` while the usage endpoint uses
 * `prefix: "¥"`, and both also set `postfix: "JPY"`. So the symbol is taken
 * from the payload rather than assumed, and the postfix is ignored for display
 * because rendering "¥100JPY" would be wrong.
 */
@Serializable
data class Money(
    val value: Double,
    val prefix: String? = null,
    val postfix: String? = null,
    val discount: Double? = null,
) {
    /**
     * e.g. `180円`.
     *
     * The API labels its currency decoration `prefix`, but that describes the
     * JSON field, not where the reader expects to see it: `円` is a Japanese
     * word that follows the amount, while the `¥` sign precedes it. Placing the
     * payload's "prefix" literally in front produces `円180`, which is wrong in
     * Japanese — so the symbol decides the position, not the field name.
     */
    fun format(): String {
        // Grouped, to match the strings the service formats itself: its order
        // history renders the same amount as "21,100円", so an ungrouped
        // "21100円" on the invoice beside it would look like a different number.
        val amount = if (value % 1.0 == 0.0) {
            GROUPED.format(value.toLong())
        } else {
            GROUPED_DECIMAL.format(value)
        }

        prefix?.takeIf(String::isNotBlank)?.let { symbol ->
            return if (symbol in TRAILING_SYMBOLS) "$amount$symbol" else "$symbol$amount"
        }
        // A postfix always trails, whatever it is.
        postfix?.takeIf { it.isNotBlank() && it != "JPY" }?.let { return "$amount$it" }
        return "${amount}円"
    }

    private companion object {
        /** Currency words that follow the amount rather than preceding it. */
        val TRAILING_SYMBOLS = setOf("円", "圓", "JPY", "yen")

        // Locale-pinned: grouping must not follow the device locale, because
        // these amounts sit next to strings the service already grouped.
        val GROUPED = DecimalFormat("#,##0", java.text.DecimalFormatSymbols(Locale.JAPAN))
        val GROUPED_DECIMAL =
            DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(Locale.JAPAN))
    }
}
