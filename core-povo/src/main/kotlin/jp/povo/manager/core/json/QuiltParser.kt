package jp.povo.manager.core.json

import jp.povo.manager.core.model.PovoWebPage
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.core.model.ProfileInfo
import jp.povo.manager.core.model.ToppingOrder
import jp.povo.manager.core.model.ToppingProduct
import jp.povo.manager.core.model.ToppingSection
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
     * The profile page: the masked card number, and the web pages it links to.
     *
     * Parsed together because they come from one request and one walk. The page
     * also carries the contractor's name, postal address and PIN mask; those
     * are deliberately not read.
     */
    fun parseProfile(raw: String): ProfileInfo {
        val components = componentObjects(raw)

        val masked = components
            .firstOrNull { PovoJson.string(it, "type") == TILE_CREDIT_CARD }
            ?.let { PovoJson.obj(it, "data") ?: it }
            ?.let { PovoJson.string(it, "description") }

        val pages = components
            .mapNotNull { component ->
                val action = PovoJson.obj(component, "action") ?: return@mapNotNull null
                if (PovoJson.string(action, "type") != ACTION_WEB_VIEW) return@mapNotNull null
                val webView = PovoJson.obj(action, "data")
                    ?.let { PovoJson.obj(it, "web_view", "webView") }
                    ?: return@mapNotNull null
                val link = PovoJson.string(webView, "link", "url") ?: return@mapNotNull null
                val kind = PovoWebPageKind.ofLink(link) ?: return@mapNotNull null
                PovoWebPage(
                    kind = kind,
                    link = link,
                    exitUrl = PovoJson.string(webView, "exit_url", "exitUrl"),
                    needsXauth = PovoJson.bool(webView, "needs_xauth", "needsXauth") ?: false,
                )
            }
            // Only the component's own action is considered, and the first tile
            // wins per kind — the PIN tile nests a second web_view under
            // `data.help` for a support article, which is not a destination.
            .distinctBy { it.kind }

        return ProfileInfo(paymentMasked = masked, webPages = pages)
    }

    /**
     * The purchasable catalogue, from the dashboard page.
     *
     * Not from `user-plan-details-v2`, which despite its name carries only what
     * the line already holds and degrades to an empty-state tile when that is
     * nothing. The catalogue lives on the dashboard as `addon-section` tiles —
     * the same place the official app reads it.
     *
     * Each product appears twice in the payload: once as the list row
     * (`data`, short display strings) and once as the confirmation sheet
     * (`action.data.product_popup`, the full name and the 3-D Secure flag).
     * Both are read, preferring the popup's fuller wording.
     */
    fun parseCatalogue(raw: String): List<ToppingSection> =
        componentObjects(raw)
            .filter { PovoJson.string(it, "type") == TILE_ADDON_SECTION }
            .mapNotNull { component ->
                val data = PovoJson.obj(component, "data") ?: return@mapNotNull null
                val products = PovoJson.array(data, "items").orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .mapNotNull(::product)
                // Sections that only carry a disclaimer ("no call topping
                // active") have no items and are not worth a heading.
                if (products.isEmpty()) return@mapNotNull null
                ToppingSection(
                    title = PovoJson.string(PovoJson.obj(data, "sectionHeader"), "title"),
                    products = products,
                )
            }

    private fun product(item: JsonObject): ToppingProduct? {
        val tile = PovoJson.obj(item, "data")
        val popup = PovoJson.obj(item, "action")
            ?.let { PovoJson.obj(it, "data") }
            ?.let { PovoJson.obj(it, "product_popup", "productPopup") }
        // The popup's id is the sku the order call wants; the tile's id matches
        // it, so either will do and whichever exists wins.
        val id = text(popup, "id") ?: text(tile, "id") ?: return null
        // Blank is treated as absent throughout: the payload uses empty strings
        // rather than nulls for fields it has nothing to say about, and an
        // elvis chain on null alone leaves a nameless row on screen.
        val name = text(popup, "title")
            ?: text(PovoJson.obj(tile, "name"), "title")
            ?: return null
        return ToppingProduct(
            id = id,
            name = name,
            validity = text(PovoJson.obj(tile, "validity"), "title"),
            price = text(popup, "price") ?: text(PovoJson.obj(tile, "price"), "title"),
            requires3ds = PovoJson.bool(popup, "is_3ds_topping", "is3dsTopping") ?: false,
        )
    }

    /** [PovoJson.string], with an empty value treated as no value. */
    private fun text(parent: kotlinx.serialization.json.JsonObject?, vararg names: String): String? =
        PovoJson.string(parent, *names)?.trim()?.takeIf(String::isNotEmpty)

    /**
     * The answer to an order.
     *
     * `challenge_url` decides everything downstream: with one, nothing has been
     * charged yet and the buyer still has to authenticate; without one, the
     * purchase is already done.
     */
    fun parseOrder(raw: String): ToppingOrder? {
        val root = PovoJson.parse(raw) ?: return null
        val result = PovoJson.obj(root, "result") ?: root
        return ToppingOrder(
            challengeUrl = PovoJson.string(result, "challenge_url", "challengeUrl"),
            orderRef = PovoJson.string(result, "order_ref", "orderRef", "order_id", "orderId"),
            paymentRef = PovoJson.string(result, "p_ref", "pRef"),
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
    private const val TILE_ADDON_SECTION = "addon-section"

    /** Other action types on the same page open deep links or a popup. */
    private const val ACTION_WEB_VIEW = "web_view"
}
