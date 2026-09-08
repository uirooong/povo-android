package jp.povo.manager.core.json

import jp.povo.manager.core.model.PovoWebPageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuiltParserTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/samples/$name")) { "missing fixture $name" }
            .bufferedReader().readText()

    // ---- toppings ---------------------------------------------------------

    @Test
    fun `reads the subscribed topping`() {
        val toppings = QuiltParser.parseToppings(fixture("quilt-user-plan-details-v2.json"))

        assertEquals(1, toppings.size)
        val topping = toppings.single()
        assertEquals("【セール】データ追加120GB（365日間）", topping.name)
        assertEquals("60.00GB / 120.00GB", topping.remaining)
    }

    @Test
    fun `groups a topping under its header tile`() {
        val topping = QuiltParser.parseToppings(fixture("quilt-user-plan-details-v2.json")).single()
        assertEquals("データ", topping.section)
    }

    @Test
    fun `splits the two-line expiry into a date and a countdown`() {
        val topping = QuiltParser.parseToppings(fixture("quilt-user-plan-details-v2.json")).single()
        assertEquals("2099年 12月 31日 午後11:59", topping.expiryDate)
        assertEquals("残り 999 日間", topping.expiryRemaining)
    }

    @Test
    fun `ignores dividers and other decoration tiles`() {
        // The fixture carries a povo-tile-divider; only the detail tile is a topping.
        assertEquals(1, QuiltParser.parseToppings(fixture("quilt-user-plan-details-v2.json")).size)
    }

    // ---- purchases --------------------------------------------------------

    @Test
    fun `reads an order from the history page`() {
        val purchases = QuiltParser.parsePurchases(fixture("quilt-order-history.json"))

        assertEquals(1, purchases.size)
        val order = purchases.single()
        assertEquals("40100000000000000", order.orderId)
        assertEquals("注文完了", order.status)
        assertEquals("2026年 2月 28日", order.purchaseDate)
        assertTrue(order.succeeded)
    }

    @Test
    fun `reads the purchased product and its already-formatted price`() {
        val order = QuiltParser.parsePurchases(fixture("quilt-order-history.json")).single()

        assertEquals(1, order.products.size)
        assertEquals("【セール】データ追加120GB（365日間）", order.title)
        // The service formats this itself, thousands separator and all; there is
        // no numeric field to derive it from.
        assertEquals("12,345円", order.price)
    }

    // ---- robustness -------------------------------------------------------

    @Test
    fun `a page with no matching tiles yields nothing rather than failing`() {
        val onlyBanners = """
            {"_id":"x","widgets":[{"type":"list","components":[
              {"type":"povo-tile-divider","data":{"color":"primary"}},
              {"type":"image-banner","data":{"title":"promo"}}
            ]}]}
        """.trimIndent()

        assertTrue(QuiltParser.parseToppings(onlyBanners).isEmpty())
        assertTrue(QuiltParser.parsePurchases(onlyBanners).isEmpty())
    }

    @Test
    fun `survives an unusable payload`() {
        listOf("", "not json", "{}", """{"widgets":null}""").forEach { raw ->
            assertTrue(QuiltParser.parseToppings(raw).isEmpty())
            assertTrue(QuiltParser.parsePurchases(raw).isEmpty())
        }
    }

    @Test
    fun `a topping with no name is skipped rather than shown blank`() {
        val nameless = """
            {"widgets":[{"components":[
              {"type":"povo-tile-plan-detail","data":{"remaining":{"value":"1GB"}}}
            ]}]}
        """.trimIndent()
        assertTrue(QuiltParser.parseToppings(nameless).isEmpty())
    }

    @Test
    fun `an order with no products still parses`() {
        val bare = """
            {"widgets":[{"components":[
              {"type":"tile-status-card","data":{"order_id":"1","status":"処理中"}}
            ]}]}
        """.trimIndent()
        val order = QuiltParser.parsePurchases(bare).single()
        assertEquals("1", order.orderId)
        assertTrue(order.products.isEmpty())
        assertNull(order.title)
    }

    @Test
    fun `reads the masked card off the profile page`() {
        val profile = QuiltParser.parseProfile(PROFILE_PAGE)

        assertEquals("xxxx-xxxx-xxxx-1234", profile.paymentMasked)
    }

    @Test
    fun `finds every page it has a way into, in the page's own order`() {
        val pages = QuiltParser.parseProfile(PROFILE_PAGE).webPages

        assertEquals(
            listOf(PovoWebPageKind.EMAIL, PovoWebPageKind.PAYMENT, PovoWebPageKind.CONTRACT),
            pages.map { it.kind },
        )
        val contract = pages.single { it.kind == PovoWebPageKind.CONTRACT }
        // The tile that carries this one has no title at all — the official app
        // supplies the label — so the link's path is the only thing that
        // identifies it.
        assertEquals("https://shop.povo.jp/manage/order?native=1&reset=true&webview=1", contract.link)
        assertEquals("/manageOrderClose", contract.exitUrl)
        // Drives whether the page is handed the account's token; the observed
        // payload sets it, and without it povo shows the page as logged out.
        assertTrue(contract.needsXauth)
    }

    @Test
    fun `ignores tiles that are not a page this app offers`() {
        // The same page links to a PIN change, a support article nested under
        // the PIN tile's `help`, and several deep links. None is a destination
        // here, and a stray entry in the UI would be worse than none.
        val links = QuiltParser.parseProfile(PROFILE_PAGE).webPages.map { it.link }

        assertTrue(links.none { "confirm-pin-change" in it })
        assertTrue(links.none { "support/pin-code" in it })
        assertTrue(links.none { "app.link" in it })
    }

    @Test
    fun `does not mistake order-history for contract management`() {
        // `/manage/order` and `/manage/order-history` differ by a suffix, so a
        // prefix match would fold the wrong page into 契約管理.
        assertEquals(
            PovoWebPageKind.CONTRACT,
            PovoWebPageKind.ofLink("https://shop.povo.jp/manage/order?webview=1"),
        )
        assertNull(PovoWebPageKind.ofLink("https://shop.povo.jp/manage/order-history"))
        assertNull(PovoWebPageKind.ofLink("not a url"))
    }

    @Test
    fun `returns nothing when the page carries neither`() {
        listOf("""{"widgets":[]}""", "not json", "").forEach { raw ->
            val profile = QuiltParser.parseProfile(raw)
            assertNull(profile.paymentMasked)
            assertTrue(profile.webPages.isEmpty())
        }
    }
}

private const val PROFILE_PAGE = """
{"_id":"profile","widgets":[
  {"header":{"title":"お客さま情報"},"type":"list","components":[
    {"type":"tile-telco-profile","data":{"name":"契約者 太郎","subtitle":"090 1234 5678"}},
    {"type":"tile-nav-right",
     "action":{"type":"web_view","data":{"web_view":{
        "link":"https://shop.povo.jp/profile/email?webview=1","exit_url":"/profileUpdateEmailSuccess",
        "needs_xauth":true}}},
     "data":{"title":"メールアドレス","desc":"example.user@example.com"}}]},
  {"header":{"title":"お支払い情報"},"type":"list","components":[
    {"type":"tile-credit-card",
     "action":{"type":"web_view","data":{"web_view":{
        "link":"https://shop.povo.jp/manage/payment-details?webview=1&native=1",
        "exit_url":"/updateCardSuccess","needs_xauth":true}}},
     "data":{"title":"ご利用中のお支払い方法","description":"xxxx-xxxx-xxxx-1234",
             "cardIcon":"https://example.invalid/mastercard.png"}},
    {"type":"tile-icon-help",
     "action":{"type":"web_view","data":{"web_view":{
        "link":"https://shop.povo.jp/manage/confirm-pin-change?native=1","exit_url":"/managePinClose",
        "needs_xauth":true}}},
     "data":{"desc":"＊＊＊＊","help":{"helpText":"もっと詳しく","action":{"type":"web_view",
        "data":{"web_view":{"link":"https://povo.jp/support/pin-code/"}}}}}}]},
  {"header":{"title":"ご契約内容"},"type":"list","components":[
    {"type":"tile-nav-right",
     "action":{"type":"deeplink","data":{"deeplink":{"link":"https://kddi-povo.app.link/order-history"}}},
     "data":{"desc":"トッピングの購入履歴はこちら"}},
    {"type":"tile-nav-right",
     "action":{"type":"web_view","data":{"web_view":{
        "link":"https://shop.povo.jp/manage/order?native=1&reset=true&webview=1",
        "exit_url":"/manageOrderClose","needs_xauth":true}}},
     "data":{"desc":"ご契約中プランの詳細確認やSIMに関するお手続きはこちら"}}]}
]}
"""
