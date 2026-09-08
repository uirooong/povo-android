package jp.povo.manager.ui.web

import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the payment page's entry URL.
 *
 * This is the whole of B-1: the page takes its session from the query string of
 * the initial request and rejects everything else — no cookie, no header, no
 * seeded storage. Getting the set wrong does not fail loudly; the page quietly
 * redirects to its own login and the change flow becomes unreachable, which is
 * exactly what happened while `device_id` was missing.
 */
@RunWith(RobolectricTestRunner::class)
// Uri needs a framework; see UsageWidgetTest for why the SDK is pinned.
@Config(sdk = [36], application = android.app.Application::class)
class PovoWebUrlTest {

    @Test
    fun `carries the whole parameter set the page needs`() {
        val built = PovoWebUrl.build(BASE, TOKEN, DEVICE, EXIT, needsXauth = true).toUri()

        // device_id is the one that was missing. The service ties a token to a
        // device: webfront/users/session answers 403 for a token presented with
        // someone else's device id, and the page bounces to login without it.
        assertEquals(DEVICE, built.getQueryParameter("device_id"))
        assertEquals(TOKEN, built.getQueryParameter("auth_token"))
        assertEquals("1", built.getQueryParameter("use_native_ekyc_api"))
        assertEquals(EXIT, built.getQueryParameter("return_url"))
        assertTrue(built.getQueryParameter("app_version")!!.isNotBlank())
    }

    @Test
    fun `leaves the server's own parameters alone`() {
        // The link came from povo's profile page; where it already made a
        // choice, that choice wins over ours.
        val built = PovoWebUrl.build(BASE, TOKEN, DEVICE, EXIT, needsXauth = true).toUri()

        assertEquals("1", built.getQueryParameter("webview"))
        assertEquals("true", built.getQueryParameter("reset"))
        assertEquals("mobile", built.getQueryParameter("update_from"))
        // Appended once, not duplicated.
        assertEquals(1, built.getQueryParameters("native").size)
        assertEquals(1, built.getQueryParameters("auth_token").size)
    }

    @Test
    fun `refuses to attach the session to a non-povo url`() {
        // The URL is stored from a server response, so a changed payload must
        // not be able to redirect the token somewhere else.
        val elsewhere = "https://evil.example.com/manage/payment-details"
        assertEquals(elsewhere, PovoWebUrl.build(elsewhere, TOKEN, DEVICE, EXIT, needsXauth = true))
        // Nor to a host that merely contains the domain.
        val lookalike = "https://povo.jp.evil.example.com/x"
        assertEquals(lookalike, PovoWebUrl.build(lookalike, TOKEN, DEVICE, EXIT, needsXauth = true))
        assertFalse(isPovoUrl(lookalike))
        assertTrue(isPovoUrl("https://shop.povo.jp/x"))
        // http is refused too, so the token cannot go out in clear text.
        assertFalse(isPovoUrl("http://shop.povo.jp/x"))
    }

    @Test
    fun `returns the link untouched when a required value is missing`() {
        // Half a session is worse than none: the page would still bounce, but
        // with the token already spent in a URL.
        assertEquals(BASE, PovoWebUrl.build(BASE, null, DEVICE, EXIT, needsXauth = true))
        assertEquals(BASE, PovoWebUrl.build(BASE, TOKEN, null, EXIT, needsXauth = true))
        assertEquals(BASE, PovoWebUrl.build(BASE, "  ", DEVICE, EXIT, needsXauth = true))
    }

    @Test
    fun `withholds the session from a page that did not ask for it`() {
        // The official app injects nothing but return_url when the action
        // carries no needs_xauth. Every tile observed on the profile page sets
        // it, so this is about not volunteering the account's token to a page
        // povo did not mark as needing it.
        val built = PovoWebUrl.build(BASE, TOKEN, DEVICE, EXIT, needsXauth = false).toUri()

        assertNull(built.getQueryParameter("auth_token"))
        assertNull(built.getQueryParameter("device_id"))
        assertNull(built.getQueryParameter("app_version"))
        assertNull(built.getQueryParameter("use_native_ekyc_api"))
        // The link's own parameters survive, and the exit path still goes on:
        // it is how the page reports it finished, not part of the session.
        assertEquals(EXIT, built.getQueryParameter("return_url"))
        assertEquals("1", built.getQueryParameter("webview"))
    }

    @Test
    fun `reads a rotated token off a webfront navigation`() {
        // How the web side hands back a renewed session. Unhandled it is also a
        // load error, since no WebView resolves the scheme.
        assertEquals(
            "new-token",
            PovoWebUrl.rotatedToken("webfront://callback?auth_token=new-token"),
        )
        assertTrue(PovoWebUrl.isRotation("webfront://anything"))
        assertFalse(PovoWebUrl.isRotation("https://shop.povo.jp/x?auth_token=t"))
        assertNull(PovoWebUrl.rotatedToken("https://shop.povo.jp/x?auth_token=t"))
        assertNull(PovoWebUrl.rotatedToken("webfront://callback"))
    }

    private companion object {
        const val BASE =
            "https://shop.povo.jp/manage/payment-details?webview=1&reset=true&native=1&update_from=mobile"
        const val TOKEN = "header.payload.signature"
        const val DEVICE = "0123456789abcdef0123456789abcdef"
        const val EXIT = "/updateCardSuccess"
    }
}
