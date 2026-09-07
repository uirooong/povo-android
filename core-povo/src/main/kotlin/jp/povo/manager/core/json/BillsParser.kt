package jp.povo.manager.core.json

import jp.povo.manager.core.model.BillEntry
import jp.povo.manager.core.model.BillStatus
import jp.povo.manager.core.model.BillsDocument
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses `GET layout/bills/info`.
 *
 * The endpoint returns a screen layout, not an invoice list: alongside the
 * billing data it carries banners, deep links and an au-ID linkage widget. The
 * invoices are three separate lists inside it. This is exactly why povo-core's
 * typed `get_bills_info()` returns nothing — it looks for a top-level `bills`
 * array, which does not exist.
 */
object BillsParser {

    fun parse(raw: String): BillsDocument? {
        val root = PovoJson.unwrap(
            PovoJson.parse(raw),
            "past_bills", "upcoming_bill", "instant_charges",
        ) ?: return null

        val past = PovoJson.obj(root, "past_bills")
        val upcoming = PovoJson.obj(root, "upcoming_bill")
        val instant = PovoJson.obj(root, "instant_charges")

        // An account with nothing billed yet still returns the three sections,
        // empty. A payload with none of them is not a billing document at all —
        // report that as null so the caller can keep its last good data instead
        // of replacing it with a convincing-looking empty timeline.
        if (past == null && upcoming == null && instant == null) return null

        return BillsDocument(
            past = entries(PovoJson.array(past, "list")) { pastBill(it) },
            upcoming = entries(PovoJson.array(upcoming, "list")) { upcomingBill(it) },
            instantCharges = entries(PovoJson.array(instant, "list")) { instantCharge(it) },
            instantChargesPaid = PovoJson.money(instant, "paid"),
            upcomingTotal = PovoJson.money(upcoming, "total"),
        )
    }

    private fun entries(array: JsonArray?, map: (JsonObject) -> BillEntry?): List<BillEntry> =
        array.orEmpty().mapNotNull { (it as? JsonObject)?.let(map) }

    private fun pastBill(node: JsonObject) = BillEntry(
        // camelCase on the wire; the snake_case spelling is a defensive fallback.
        billId = PovoJson.string(node, "billId", "bill_id"),
        title = PovoJson.string(node, "title"),
        // Both spellings of the suffix ship in the same object with identical
        // values; either will do.
        subtitle = PovoJson.string(node, "subtitle", "subtitleSuffix", "subtitle_suffix"),
        status = when (PovoJson.string(node, "type")?.uppercase()) {
            "PAID" -> BillStatus.PAID
            null -> BillStatus.UNKNOWN
            else -> BillStatus.UNPAID
        },
        amount = PovoJson.money(node, "total", "amount", "price"),
        timeEpochMillis = PovoJson.epochMillis(node, "time", "date"),
        hasPdf = hasPdfAction(node),
    )

    private fun upcomingBill(node: JsonObject) = BillEntry(
        billId = null, // not yet issued, so there is nothing to download
        title = PovoJson.string(node, "label", "title"),
        subtitle = PovoJson.string(node, "subtitle"),
        status = BillStatus.UPCOMING,
        amount = PovoJson.money(node, "amount", "total", "price"),
        timeEpochMillis = PovoJson.epochMillis(node, "time", "date"),
    )

    private fun instantCharge(node: JsonObject) = BillEntry(
        billId = PovoJson.string(node, "billId", "bill_id", "id"),
        title = PovoJson.string(node, "title", "label", "name"),
        subtitle = PovoJson.string(node, "subtitle", "description"),
        status = BillStatus.INSTANT,
        amount = PovoJson.money(node, "price", "paid", "amount", "total"),
        timeEpochMillis = PovoJson.epochMillis(node, "date", "time", "created_at"),
    )

    /**
     * An entry advertises a PDF through `actions[].type == "pdf"`.
     *
     * The action's own `link` is empty in practice, so it is only a marker —
     * the bytes come from `download_bill_pdf(billId)`.
     */
    private fun hasPdfAction(node: JsonObject): Boolean =
        (node["actions"] as? JsonArray).orEmpty().any { action ->
            ((action as? JsonObject)?.get("type"))?.jsonPrimitive?.content == "pdf"
        }
}
