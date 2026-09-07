package jp.povo.manager.core.model

/**
 * Which section of the billing document an entry came from, and — for past
 * invoices — what the payload's own `type` field said.
 */
enum class BillStatus {
    /** `past_bills.list[].type == "PAID"`. */
    PAID,

    /** A past invoice whose `type` was something other than PAID. */
    UNPAID,

    /** From `upcoming_bill.list[]` — an estimate, not yet issued. */
    UPCOMING,

    /** From `instant_charges.list[]` — a one-off charge already settled. */
    INSTANT,

    UNKNOWN,
}

/**
 * One line in the billing timeline.
 *
 * [billId] is only present for issued invoices; upcoming estimates have none,
 * and it is the key [jp.povo.manager.core.PovoAccountClient.downloadBillPdf]
 * needs, so a null here means there is no PDF to fetch.
 */
data class BillEntry(
    val billId: String?,
    val title: String?,
    val subtitle: String?,
    val status: BillStatus,
    val amount: Money?,
    /** Epoch **milliseconds** — the API sends `time` in ms, not seconds. */
    val timeEpochMillis: Long?,
    /** True when the entry advertises a downloadable invoice. */
    val hasPdf: Boolean = false,
)

/**
 * The parsed `layout/bills/info` document.
 *
 * The endpoint returns a screen layout rather than a list of invoices, which is
 * why povo-core's typed `get_bills_info()` finds nothing: it looks for a
 * top-level `bills` array and the invoices actually live under
 * `past_bills.list[]`.
 */
data class BillsDocument(
    val past: List<BillEntry> = emptyList(),
    val upcoming: List<BillEntry> = emptyList(),
    val instantCharges: List<BillEntry> = emptyList(),
    /** `instant_charges.paid` — total already charged this cycle. */
    val instantChargesPaid: Money? = null,
    /** `upcoming_bill.total` — the estimate for the next invoice. */
    val upcomingTotal: Money? = null,
) {
    /** Everything in one list, newest first — what the bills screen renders. */
    val all: List<BillEntry>
        get() = (upcoming + instantCharges + past)
            .sortedByDescending { it.timeEpochMillis ?: Long.MAX_VALUE }
}
