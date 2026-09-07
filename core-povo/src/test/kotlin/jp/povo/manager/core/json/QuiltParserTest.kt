package jp.povo.manager.core.json

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
    fun `reads the payment method off the profile page`() {
        val payment = requireNotNull(QuiltParser.parsePaymentMethod(PROFILE_PAGE))

        assertEquals("xxxx-xxxx-xxxx-1234", payment.maskedNumber)
        assertEquals("ご利用中のお支払い方法", payment.title)
        assertEquals(
            "https://shop.povo.jp/manage/payment-details?webview=1&native=1",
            payment.updateUrl,
        )
        assertEquals("/updateCardSuccess", payment.exitUrl)
        // Drives whether the page is handed the account's token; the observed
        // payload sets it, and without it povo shows the page as logged out.
        assertTrue(payment.needsXauth)
    }

    @Test
    fun `does not mistake another web_view tile for the card`() {
        // The same page hands out web_view tiles for the email address, the
        // postal address and the PIN. Matching on the action rather than the
        // tile type would pick up whichever came first — here, the email.
        val payment = QuiltParser.parsePaymentMethod(PROFILE_PAGE)
        assertEquals("xxxx-xxxx-xxxx-1234", payment?.maskedNumber)
    }

    @Test
    fun `returns null when the page carries no card`() {
        assertNull(QuiltParser.parsePaymentMethod("""{"widgets":[]}"""))
        assertNull(QuiltParser.parsePaymentMethod("not json"))
        assertNull(QuiltParser.parsePaymentMethod(""))
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
             "cardIcon":"https://example.invalid/mastercard.png"}}]}
]}
"""
