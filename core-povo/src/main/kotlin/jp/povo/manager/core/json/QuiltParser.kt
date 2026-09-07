package jp.povo.manager.core.json

import jp.povo.manager.core.model.PaymentMethod
import jp.povo.manager.core.model.Purchase
import jp.povo.manager.core.model.PurchasedProduct
import jp.povo.manager.core.model.Topping
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Parses Quilt pages — the server-composed screens behind
 * `/api/v1/quilt/page/{page}`.
 *
 * Quilt routes are **not localized**: they sit directly under `/api/v1/`, with
 * none of the `/{version}/jp/{locale}/mobile/` shape every other endpoint uses.
 * Addressing them through the ordinary path builder yields a 404, which is why
 * povo-core needs its raw-path accessor to reach them at all.
 *
 * Every page has the same envelope — `widgets[].components[]`, each component a
 * `{type, data}` tile — so both pages here are the same walk with a different
 * tile vocabulary. Unknown tile types are ignored rather than treated as an
 * error: the server adds banners and promos to these screens freely.
 */
object QuiltParser {

    /**
     * Subscribed toppings from `user-plan-details-v2`.
     *
     * This is the only endpoint found that reports them. The
     * `addons_subscribed` block that static analysis pointed at does not appear
     * in `account/usage/plan/get` responses, and every other candidate
     * (`account/plan/details/get`, `account/addon/topup/all/get`) fails
     * server-side.
     */
    fun parseToppings(raw: String): List<Topping> {
        var section: String? = null
        val toppings = mutableListOf<Topping>()

        components(raw).forEach { (type, data) ->
            when {
                // A header tile labels the group of detail tiles beneath it.
                type.endsWith("plan-header") -> section = PovoJson.string(data, "title")

                type.endsWith("plan-detail") -> {
                    val name = PovoJson.string(PovoJson.obj(data, "name"), "title")
                        ?: PovoJson.string(data, "title")
                        ?: return@forEach
                    toppings += Topping(
                        section = section,
                        name = name,
                        remaining = PovoJson.string(PovoJson.obj(data, "remaining"), "value"),
                        expiry = PovoJson.string(PovoJson.obj(data, "expiry"), "value"),
                    )
                }
            }
        }
        return toppings
    }

    /** Order history from the `order-history` page. */
    fun parsePurchases(raw: String): List<Purchase> =
        components(raw).mapNotNull { (type, data) ->
            if (!type.contains("status-card")) return@mapNotNull null
            Purchase(
                orderId = PovoJson.string(data, "order_id", "orderId"),
                status = PovoJson.string(data, "status"),
                orderStatus = PovoJson.string(data, "order_status", "orderStatus"),
                purchaseDate = PovoJson.string(data, "purchase_date", "order_date", "date"),
                products = (data?.get("products") as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .map { product ->
                        PurchasedProduct(
                            name = PovoJson.string(product, "product_name", "name", "title"),
                            price = PovoJson.string(product, "price"),
                        )
                    },
            )
        }

    /**
     * The account's payment method, from the `profile` page.
     *
     * Keyed on the tile type rather than on the presence of a `web_view`
     * action: the same page hands out `web_view` tiles for the email address,
     * the postal address and the PIN, so matching on the action would pick up
     * whichever happened to come first.
     */
    fun parsePaymentMethod(raw: String): PaymentMethod? =
        componentObjects(raw)
            .firstOrNull { PovoJson.string(it, "type") == TILE_CREDIT_CARD }
            ?.let { component ->
                val data = PovoJson.obj(component, "data") ?: component
                val masked = PovoJson.string(data, "description") ?: return@let null
                val webView = PovoJson.obj(component, "action")
                    ?.let { PovoJson.obj(it, "data") }
                    ?.let { PovoJson.obj(it, "web_view", "webView") }
                PaymentMethod(
                    maskedNumber = masked,
                    title = PovoJson.string(data, "title"),
                    brandIconUrl = PovoJson.string(data, "cardIcon", "icon"),
                    updateUrl = webView?.let { PovoJson.string(it, "link", "url") },
                    exitUrl = webView?.let { PovoJson.string(it, "exit_url", "exitUrl") },
                    needsXauth = webView
                        ?.let { PovoJson.bool(it, "needs_xauth", "needsXauth") } ?: false,
                )
            }

    /**
     * Flattens `widgets[].components[]` into `(type, data)` pairs.
     *
     * A tile whose `data` is missing falls back to the component itself, since
     * some tiles carry their fields inline rather than nested.
     */
    private fun components(raw: String): List<Pair<String, JsonObject?>> =
        componentObjects(raw).mapNotNull { component ->
            val type = PovoJson.string(component, "type") ?: return@mapNotNull null
            type to (PovoJson.obj(component, "data") ?: component)
        }

    /**
     * The components as whole objects.
     *
     * [components] discards everything but `type` and `data`, which is enough
     * for the tiles that only display text. A tile that also carries an
     * `action` — the payment method's change link, for one — needs the sibling
     * fields too.
     */
    private fun componentObjects(raw: String): List<JsonObject> {
        val root = PovoJson.parse(raw) ?: return emptyList()
        val widgets = PovoJson.array(root, "widgets", "pageWidgets") ?: return emptyList()
        return widgets
            .mapNotNull { it as? JsonObject }
            .flatMap { widget -> PovoJson.array(widget, "components", "items").orEmpty() }
            .mapNotNull { it as? JsonObject }
    }

    private const val TILE_CREDIT_CARD = "tile-credit-card"
}
