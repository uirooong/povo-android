package jp.povo.manager.core.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue and the order response, pinned to payloads observed live.
 *
 * Both matter more than usual: one drives what a person is offered, and the
 * other decides whether the app believes money has changed hands.
 */
class StoreParserTest {

    @Test
    fun `reads the catalogue off the dashboard`() {
        val sections = QuiltParser.parseCatalogue(DASHBOARD)

        assertEquals(listOf("使い放題トッピング", "データトッピング"), sections.map { it.title })
        val product = sections.first().products.single()
        assertEquals("905fe660-f5ad-4a57-a916-4545ed725df8", product.id)
        // The popup's wording wins over the tile's short label.
        assertEquals("データ使い放題（1時間）", product.name)
        assertEquals("1時間", product.validity)
        assertEquals("110円", product.price)
        assertFalse(product.requires3ds)
    }

    @Test
    fun `keeps the discounted price exactly as quoted`() {
        // The service renders the strike-through itself. Re-deriving a number
        // here would quote a price povo never offered.
        val product = QuiltParser.parseCatalogue(DASHBOARD)[1].products.single()
        assertEquals("27,500円 -> 25,100円", product.price)
        assertTrue(product.requires3ds)
    }

    @Test
    fun `falls back to the tile name when the popup title is blank`() {
        // Observed live: some tiles carry an empty string rather than no key,
        // which an elvis chain on null alone lets through as a nameless row.
        val product = QuiltParser.parseCatalogue(BLANK_TITLE).single().products.single()

        assertEquals("データ使い放題", product.name)
        assertEquals("2時間 40回分", product.validity)
        assertEquals("3,600円*", product.price)
    }

    @Test
    fun `drops a section that carries only a disclaimer`() {
        // The call-topping section is present with no items when nothing is
        // active; a bare heading with nothing under it is not worth showing.
        assertTrue(QuiltParser.parseCatalogue(DISCLAIMER_ONLY).isEmpty())
    }

    @Test
    fun `an order awaiting authentication is not a purchase`() {
        val order = requireNotNull(QuiltParser.parseOrder(CHALLENGE_RESPONSE))

        assertTrue(order.needsChallenge)
        assertEquals("https://front.secure.example.invalid/auth/brw/callback?transId=x", order.challengeUrl)
        assertEquals("13100000000000000", order.orderRef)
        assertEquals("P-100000000000000", order.paymentRef)
    }

    @Test
    fun `an order with no challenge is already done`() {
        val order = requireNotNull(QuiltParser.parseOrder("""{"success":true,"result":{"order_ref":"1"}}"""))

        assertFalse(order.needsChallenge)
        assertNull(order.challengeUrl)
        // An empty string is the service saying "no challenge", not a URL.
        val blank = requireNotNull(
            QuiltParser.parseOrder("""{"result":{"challenge_url":"","order_ref":"1"}}"""),
        )
        assertFalse(blank.needsChallenge)
    }

    @Test
    fun `rubbish parses to nothing rather than to a completed purchase`() {
        assertNull(QuiltParser.parseOrder("not json"))
        assertNull(QuiltParser.parseOrder(""))
        assertTrue(QuiltParser.parseCatalogue("""{"widgets":[]}""").isEmpty())
        assertTrue(QuiltParser.parseCatalogue("not json").isEmpty())
    }
}

private const val CHALLENGE_RESPONSE = """
{"success":true,
 "result":{"challenge_url":"https://front.secure.example.invalid/auth/brw/callback?transId=x",
           "order_ref":"13100000000000000",
           "p_ref":"P-100000000000000"}}
"""

private const val DASHBOARD = """
{"_id":"dashboard-v2","widgets":[{"type":"list","components":[
  {"type":"povo-tile-banner","data":{"id":"promo"}},
  {"type":"addon-section","data":{
     "sectionHeader":{"title":"使い放題トッピング"},
     "items":[
       {"data":{"id":"905fe660-f5ad-4a57-a916-4545ed725df8",
                "name":{"title":"データ使い放題"},
                "validity":{"title":"1時間"},
                "price":{"title":"110円"}},
        "action":{"data":{"product_popup":{
           "id":"905fe660-f5ad-4a57-a916-4545ed725df8",
           "title":"データ使い放題（1時間）",
           "price":"110円",
           "is_3ds_topping":false}}}}]}},
  {"type":"addon-section","data":{
     "sectionHeader":{"title":"データトッピング"},
     "items":[
       {"data":{"id":"0dfc5709-70ee-46fd-9edf-b4d87b26f984",
                "name":{"title":"120GB/365日間"},
                "price":{"title":"27,500円 -> 25,100円"}},
        "action":{"data":{"product_popup":{
           "id":"0dfc5709-70ee-46fd-9edf-b4d87b26f984",
           "title":"データ追加120GB（365日間）",
           "price":"27,500円 -> 25,100円",
           "is_3ds_topping":true}}}}]}}
]}]}
"""

private const val BLANK_TITLE = """
{"widgets":[{"components":[
  {"type":"addon-section","data":{
     "sectionHeader":{"title":"月初めにおすすめ"},
     "items":[
       {"data":{"id":"d2b71f4d-debf-4912-81de-944bc627b2c0",
                "name":{"title":"データ使い放題"},
                "validity":{"title":"2時間 40回分"},
                "price":{"title":"3,600円*"}},
        "action":{"data":{"product_popup":{
           "id":"d2b71f4d-debf-4912-81de-944bc627b2c0",
           "title":"","price":"","is_3ds_topping":true}}}}]}}
]}]}
"""

private const val DISCLAIMER_ONLY = """
{"widgets":[{"components":[
  {"type":"addon-section","data":{
     "sectionHeader":{"title":"適用中の通話トッピング（継続購入）"},
     "items":[{"data":{"title":"現在適用中の通話トッピングはありません"},"type":"addon-disclaimer"}]}}
]}]}
"""
