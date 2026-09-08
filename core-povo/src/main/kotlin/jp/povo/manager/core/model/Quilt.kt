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

/**
 * The profile page, reduced to the two things this app takes from it.
 *
 * That page is povo's own account screen and carries considerably more — the
 * contractor's name, a postal address, a PIN mask. None of it is parsed or
 * stored: the app reads the masked card number, which it displays, and the
 * links to the pages it offers a way into.
 */
@Serializable
data class ProfileInfo(
    /**
     * e.g. `xxxx-xxxx-xxxx-1234`. Already masked by the service and kept
     * verbatim — the app never sees a full card number, and re-deriving the
     * format would only risk disagreeing with the official app.
     */
    val paymentMasked: String? = null,
    /** In the order the page listed them, which is povo's own ordering. */
    val webPages: List<PovoWebPage> = emptyList(),
)

/**
 * One of povo's own web pages, as the profile page hands it out.
 *
 * These are pages rather than API calls by povo's design: changing a card or an
 * email address happens on `shop.povo.jp`, so the details are entered there and
 * never pass through this app.
 */
@Serializable
data class PovoWebPage(
    val kind: PovoWebPageKind,
    /** The entry URL, with the query the service put on it. */
    val link: String,
    /**
     * Path the page navigates to once the change succeeded. It is the only
     * completion signal available — there is no callback and no status
     * endpoint — so it is what tells the host screen to close.
     */
    val exitUrl: String? = null,
    /**
     * Whether the page needs the account's token to identify the user.
     * Observed `true` for all three: without it the page loads but shows
     * nobody signed in.
     */
    val needsXauth: Boolean = false,
)

/**
 * The profile-page destinations this app offers, keyed by the path of the link.
 *
 * Matching on the path rather than on the tile type or its title is not a
 * stylistic choice. Two of these three tiles are the same `tile-nav-right`
 * type, and the 契約管理 tile carries **no title at all** — the official app
 * supplies that label itself — so the path is the only field that tells them
 * apart. Unknown paths are ignored, which is what keeps a new tile on that
 * page from turning into a mystery entry in the UI.
 */
@Serializable
enum class PovoWebPageKind(private val path: String) {
    /** メールアドレスの変更. */
    EMAIL("/profile/email"),

    /** お支払い方法の変更. */
    PAYMENT("/manage/payment-details"),

    /** 契約管理 — プラン詳細、SIM の手続き、解約や MNP. */
    CONTRACT("/manage/order"),
    ;

    companion object {
        /**
         * The kind [link] points at, or null if it is not one of ours.
         *
         * Compared exactly, not by prefix: the same page also links to
         * `/manage/order-history`, which a `startsWith` would swallow into
         * [CONTRACT].
         */
        fun ofLink(link: String): PovoWebPageKind? {
            val path = pathOf(link)
            return entries.firstOrNull { it.path == path }
        }

        private fun pathOf(link: String): String {
            val afterHost = link.substringAfter("://", link).let {
                val slash = it.indexOf('/')
                if (slash < 0) "/" else it.substring(slash)
            }
            return afterHost.substringBefore('?').substringBefore('#').trimEnd('/')
        }
    }
}
