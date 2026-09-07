package jp.povo.manager.ui.payment

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * The half of povo's web bridge that the payment page actually needs.
 *
 * `shop.povo.jp` is a single-page app that does not read the token from a
 * request header — it asks the host app for it:
 *
 * ```
 * page → POVO_ANDROID_WEB_BRIDGE.refreshXAuthToken("setAndroidAccessToken", promiseId)
 * app  → window.setAndroidAccessToken(promiseId, '{"X-Auth":"<token>"}')
 * ```
 *
 * The page resolves a promise keyed by `promiseId` with the `X-Auth` value and
 * uses it for its own requests; with no bridge present it logs
 * "Android bridge has not been initialized" and falls back to showing its own
 * login form, which is what this app did before.
 *
 * Only what the payment page needs is exposed. The real bridge also carries
 * eKYC, eSIM activation and file-download handlers; none of them are wanted
 * here, and a javascript interface is the wrong place to be generous.
 *
 * @param origin returns the page currently loaded, so a request for the token
 *   can be refused unless it came from povo. The interface is installed on the
 *   WebView as a whole, and a card change can legitimately hand off to a bank's
 *   3-D Secure page — this is what stops that page, or anything else the flow
 *   redirects to, from asking for the account's token.
 */
class PovoWebBridge(
    private val webView: WebView,
    private val token: String?,
    private val origin: () -> String?,
) {

    private val main = Handler(Looper.getMainLooper())

    /**
     * The app version, as a JSON string — the page parses the return value.
     *
     * Synchronous, unlike the token: the page reads it as
     * `JSON.parse(bridge.getAppVersion()).app_version` and, when the call
     * throws, decides the environment is unsupported ("Device is not
     * supported") and stops asking the bridge for anything else.
     *
     * The `-JP` suffix the API sends is dropped because the page compares this
     * against dotted numbers to gate features.
     */
    @JavascriptInterface
    fun getAppVersion(): String {
        Log.i(TAG, "getAppVersion")
        return JSONObject().put("app_version", APP_VERSION_NUMERIC).toString()
    }

    @JavascriptInterface
    fun refreshXAuthToken(callbackName: String, promiseId: String) {
        Log.i(TAG, "refreshXAuthToken(callback=$callbackName)")
        val current = origin()
        if (current == null || !isPovoUrl(current)) {
            Log.w(TAG, "refused a token request from $current")
            return
        }
        if (token.isNullOrBlank()) {
            Log.w(TAG, "no token to hand over")
            return
        }
        if (!SAFE_CALLBACK.matches(callbackName)) {
            // The page chooses the callback name and it is interpolated into
            // script, so anything but a plain identifier is refused rather
            // than evaluated.
            Log.w(TAG, "refused an implausible callback name")
            return
        }

        // JSONObject rather than string building: it escapes both the payload
        // and the promise id, so neither can break out of the literal.
        val payload = JSONObject().put(TOKEN_KEY, token).toString()
        val script = "window.$callbackName(${JSONObject.quote(promiseId)}, ${JSONObject.quote(payload)});"

        // The interface is called on a WebView worker thread; evaluating has
        // to happen on the thread that owns the view.
        main.post { webView.evaluateJavascript(script, null) }
        Log.i(TAG, "handed the token to $callbackName")
    }

    companion object {
        /** The name the page looks for on `window`. */
        const val NAME = "POVO_ANDROID_WEB_BRIDGE"

        private const val TAG = "PovoWebBridge"

        /** [jp.povo.manager.core.PovoProtocol.APP_VERSION] without its locale suffix. */
        private val APP_VERSION_NUMERIC =
            jp.povo.manager.core.PovoProtocol.APP_VERSION.substringBefore('-')
        private const val TOKEN_KEY = "X-Auth"
        private val SAFE_CALLBACK = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    }
}
