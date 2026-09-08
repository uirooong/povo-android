package jp.povo.manager.core.model

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * povo2.0's long-idle rule: buy no paid topping for 180 days and the line is
 * suspended, then closed.
 *
 * The rule is documented at `faq.povo.jp` (category 35; No.904 and No.896 carry
 * the arithmetic) and reduces to one sentence: **the count starts the day after
 * the last topping expires**, not the day it was bought. With several toppings
 * held, only the latest expiry matters — povo's own worked example takes a
 * 31 January expiry and starts counting on 1 February.
 *
 * The expiry that anchors all of this is entered by the reader rather than read
 * from the service. That is deliberate. The plan page returns only *currently
 * held* toppings, and povo itself keeps no list of expired ones, so the anchor
 * disappears from the API at exactly the moment it starts to matter; the order
 * history has purchase dates but hides the duration inside the product's name
 * (`データ追加120GB（365日間）`), which not every product spells out. Every
 * automatic route is therefore an estimate, and a wrong suspension date is
 * worse than none — it grants false confidence about a line going dead.
 *
 * Not modelled: the exemption for a period whose metered call and SMS charges
 * exceed ¥660 including tax. Deciding that would mean apportioning invoices
 * across the window, and getting it wrong would silently push the date later —
 * the one direction that costs the reader their line. The UI states the
 * exemption instead so it can be judged by a person.
 */
object Suspension {

    /** Days of grace from the anchor, per the FAQ. */
    const val WINDOW_DAYS = 180L

    /** Days from suspension to the contract being closed. */
    const val TERMINATION_GRACE_DAYS = 30L

    /**
     * The forecast for one line, or null if [expiryDate] is not a date.
     *
     * @param expiryDate the last topping's expiry, `YYYY-MM-DD`.
     * @param today the day to measure from; injected so the boundary is
     *   testable without waiting for midnight.
     */
    fun forecast(expiryDate: String, today: LocalDate): SuspensionForecast? {
        val expiry = parseDate(expiryDate) ?: return null
        // "翌日から180日間" — the window opens the day after expiry and runs 180
        // days, so its last day is expiry + 180 and suspension can follow from
        // the day after that.
        val suspendsOn = expiry.plusDays(WINDOW_DAYS + 1)
        return SuspensionForecast(
            expiry = expiry,
            suspendsOn = suspendsOn,
            terminatesOn = suspendsOn.plusDays(TERMINATION_GRACE_DAYS),
            daysLeft = ChronoUnit.DAYS.between(today, suspendsOn).toInt(),
        )
    }

    /**
     * The expiry implied by buying a topping of [durationDays] on
     * [purchaseDate], or null if the date will not parse.
     *
     * `purchase + duration`, checked against povo twice rather than reasoned
     * about: the FAQ's own example expires a 30-day topping bought 1 January on
     * 31 January, and a real account holding a 365-day topping bought
     * 2026-02-28 shows 2027-02-28. Counting the purchase day as day one would
     * be a day early and would move every suspension date with it.
     */
    fun expiryFrom(purchaseDate: String, durationDays: Int): String? {
        if (durationDays < 1) return null
        val purchase = parseDate(purchaseDate) ?: return null
        return purchase.plusDays(durationDays.toLong()).toString()
    }

    /** Lenient `YYYY-MM-DD`; anything else is a null rather than a throw. */
    fun parseDate(value: String?): LocalDate? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty()) return null
        return try {
            LocalDate.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * When one line is due to be suspended, and how long that leaves.
 *
 * [daysLeft] goes negative once the date has passed. That is kept rather than
 * clamped: povo suspends lines "順次" — in batches — so a line can still be
 * live days after the date, and showing "0日" for both cases would hide the
 * difference between "today" and "you are already overdue".
 */
data class SuspensionForecast(
    val expiry: LocalDate,
    val suspendsOn: LocalDate,
    val terminatesOn: LocalDate,
    val daysLeft: Int,
) {
    val overdue: Boolean get() = daysLeft < 0
}
