package jp.povo.manager.core.json

import kotlinx.serialization.json.JsonObject

/**
 * Reads the contract details povo-core's typed `UserProfile` leaves out.
 *
 * Only the line's activation date really needs this — the contractor's name is
 * already on the typed struct — but it is the one field the app displays that
 * the Rust model does not carry, and reading it here costs nothing next to a
 * core change.
 */
object ProfileParser {

    /**
     * The line's 開通日 as `YYYY-MM-DD`, or null when the payload has none.
     *
     * `activation_date` is the current line's; `initial_activation_date` is the
     * account's first ever, which is the same value unless the line was
     * re-issued — so it is only a fallback. The service sends ISO-8601 with a
     * time (`2024-01-01T00:00:00.000Z`), and the time is dropped rather than
     * converted: it is UTC midnight, so shifting it into JST would move the
     * date forward a day and show the wrong 開通日.
     */
    fun activationDate(raw: String): String? {
        val telco = telcoInfo(raw) ?: return null
        val value = PovoJson.string(telco, "activation_date")
            ?: PovoJson.string(telco, "initial_activation_date")
            ?: return null
        return value.substringBefore('T').takeIf(String::isNotBlank)
    }

    /** The contractor's name, for payloads read without the typed struct. */
    fun customerName(raw: String): String? =
        telcoInfo(raw)?.let { PovoJson.string(it, "customer_name") }

    /**
     * `GET users?include_telco=true` comes back both bare and wrapped in
     * `result`, so [PovoJson.unwrap] handles both — the same defensiveness
     * povo-core applies on the Rust side.
     */
    private fun telcoInfo(raw: String): JsonObject? {
        val root = PovoJson.unwrap(PovoJson.parse(raw), "telco_info") ?: return null
        return PovoJson.obj(root, "telco_info")
    }
}
