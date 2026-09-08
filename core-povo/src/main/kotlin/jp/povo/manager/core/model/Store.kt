package jp.povo.manager.core.model

import kotlinx.serialization.Serializable

/**
 * One purchasable topping, as the dashboard advertises it.
 *
 * Every text here is the service's own rendering, kept verbatim for the same
 * reason [Topping] keeps its strings: `price` arrives as `21,600円` or
 * `45,140円 -> 42,740円`, already carrying the discount, and re-deriving it
 * would invent a number povo did not quote. Buying is charged against what the
 * service decides, not against anything computed here.
 */
@Serializable
data class ToppingProduct(
    /** The `sku` the order call takes. */
    val id: String,
    /** e.g. `データ追加120GB（365日間）`. */
    val name: String,
    /** e.g. `120GB/365日間`, when the tile carries one. */
    val validity: String? = null,
    /** e.g. `21,600円`. Already formatted, discounts included. */
    val price: String? = null,
    /**
     * Whether buying this triggers a 3-D Secure challenge.
     *
     * Advisory, not a contract: the issuer decides at authorisation time, and a
     * "frictionless" approval skips the challenge even when this is true. The
     * order response is what actually says whether one is needed.
     */
    val requires3ds: Boolean = false,
)

/** A titled group of products, as the dashboard lays them out. */
@Serializable
data class ToppingSection(
    /** e.g. `データトッピング`. Null when the section came without a header. */
    val title: String? = null,
    val products: List<ToppingProduct> = emptyList(),
)

/**
 * What the service answered when an order was placed.
 *
 * A successful call does **not** mean a completed purchase. When
 * [challengeUrl] is present the order is pending the card issuer's
 * authentication, and nothing is charged until that page is completed —
 * abandoning it leaves no order in the history at all, which was confirmed
 * against a live account.
 */
@Serializable
data class ToppingOrder(
    /**
     * The 3-D Secure page to send the buyer to, or null when the payment went
     * through without a challenge.
     *
     * This is on the payment processor's domain, not povo's.
     */
    val challengeUrl: String? = null,
    val orderRef: String? = null,
    /** The processor's own reference. */
    val paymentRef: String? = null,
) {
    /** Whether the buyer still has to authenticate before anything is charged. */
    val needsChallenge: Boolean get() = !challengeUrl.isNullOrBlank()
}
