package jp.povo.manager.core.model

import kotlinx.serialization.Serializable

/**
 * One currently-subscribed topping.
 *
 * The values are display strings, kept exactly as the service rendered them.
 * That is deliberate: this endpoint returns a screen, not data — `remaining`
 * arrives already formatted as `55.45GB / 120.00GB` and `expiry` as
 * `2027年 2月 28日 午後11:59\n残り 174 日間`. Re-parsing those into numbers only
 * to re-render them would invent precision and risk disagreeing with the
 * official app over the same contract.
 *
 * The numeric remaining figure is available separately and properly typed from
 * [PlanUsage].
 */
@Serializable
data class Topping(
    /** The header tile this topping appeared under, e.g. `データ`. */
    val section: String? = null,
    val name: String,
    /** e.g. `55.45GB / 120.00GB`. */
    val remaining: String? = null,
    /** e.g. `2027年 2月 28日 午後11:59` plus a remaining-days line. */
    val expiry: String? = null,
) {
    /** [expiry] without the trailing "残り N 日間" line, for compact display. */
    val expiryDate: String? get() = expiry?.lineSequence()?.firstOrNull()?.trim()

    /** The "残り N 日間" line on its own, when present. */
    val expiryRemaining: String? get() =
        expiry?.lineSequence()?.drop(1)?.firstOrNull()?.trim()?.takeIf(String::isNotBlank)
}

@Serializable
data class PurchasedProduct(
    val name: String? = null,
    /** Already formatted by the service, e.g. `21,100円`. */
    val price: String? = null,
)

/** One row of the order history. */
@Serializable
data class Purchase(
    val orderId: String? = null,
    /** Human-readable state, e.g. `注文完了`. */
    val status: String? = null,
    /** Machine-readable state, e.g. `success`. */
    val orderStatus: String? = null,
    /** Already formatted by the service, e.g. `2026年 2月 28日`. */
    val purchaseDate: String? = null,
    val products: List<PurchasedProduct> = emptyList(),
) {
    val title: String? get() = products.firstOrNull()?.name
    val price: String? get() = products.firstOrNull()?.price
    val succeeded: Boolean get() = orderStatus.equals("success", ignoreCase = true)
}
