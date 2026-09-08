package jp.povo.manager.ui.web

import android.net.Uri
import androidx.core.net.toUri
import jp.povo.manager.core.PovoProtocol

/**
 * Builds the entry URL for one of povo's own pages, the way the official app
 * does.
 *
 * The same parameter set serves every `needs_xauth` page — the payment method,
 * the email address, contract management — because the official app applies it
 * from the action, not from the destination.
 *
 * These pages will not accept a session from a header, a cookie or seeded
 * localStorage — none of which the official app uses either. They read the
 * session out of the **query string of the initial document request**, and
 * need the whole set, not just the token: `device_id` alongside `auth_token`
 * is the part that was missing while this app kept landing on `/web/login`.
 * That matches what the service itself says — `webfront/users/session` accepts
 * a token only when `X-Deviceid` is the account's own.
 *
 * Established by static analysis of the official APK 1.70.0-JP
 * (`WebViewFragment.onViewCreated`, applied when an action carries
 * `needs_xauth`); see `docs/POVO-WEBVIEW-FINDINGS.md`.
 */
internal object PovoWebUrl {

    /**
     * @param base the `web_view.link` the profile page handed out, already
     *   carrying some of `webview=1&reset=true&native=1` itself.
     * @param exitPath the action's `exit_url`, passed on as `return_url`.
     * @return [base] untouched when it is not a povo URL, or when there is no
     *   token to add — a half-built URL would only fail less obviously.
     */
    fun build(
        base: String,
        authToken: String?,
        deviceId: String?,
        exitPath: String?,
    ): String {
        val token = authToken?.takeIf(String::isNotBlank) ?: return base
        val device = deviceId?.takeIf(String::isNotBlank) ?: return base
        if (!isPovoUrl(base)) return base
        val uri = runCatching { base.toUri() }.getOrNull() ?: return base

        val builder = uri.buildUpon()
        // Only what the link does not already carry: the server was given this
        // URL by its own profile page, so its existing parameters win.
        uri.addMissing(builder, DEVICE_ID, device)
        uri.addMissing(builder, AUTH_TOKEN, token)
        uri.addMissing(builder, WEBVIEW, "1")
        uri.addMissing(builder, NATIVE, "1")
        uri.addMissing(builder, RESET, "true")
        // The official app sends this on every needs_xauth page, not only eKYC
        // ones, and the page branches on it during boot.
        uri.addMissing(builder, NATIVE_EKYC, "1")
        uri.addMissing(builder, APP_VERSION, PovoProtocol.APP_VERSION)
        exitPath?.takeIf(String::isNotBlank)?.let { uri.addMissing(builder, RETURN_URL, it) }
        return builder.build().toString()
    }

    private fun Uri.addMissing(builder: Uri.Builder, name: String, value: String) {
        if (getQueryParameter(name) == null) builder.appendQueryParameter(name, value)
    }

    /**
     * The token from a `webfront://` navigation, or null if there is none.
     *
     * That scheme is how the web side rotates the session: the official app
     * intercepts it, cancels the navigation, and keeps the `auth_token` as its
     * new session. Unhandled it would also be a load error, since no WebView
     * can resolve the scheme.
     */
    fun rotatedToken(url: String): String? {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return null
        if (!uri.scheme.equals(ROTATION_SCHEME, ignoreCase = true)) return null
        return uri.getQueryParameter(AUTH_TOKEN)?.takeIf(String::isNotBlank)
    }

    fun isRotation(url: String): Boolean =
        runCatching { url.toUri().scheme.equals(ROTATION_SCHEME, ignoreCase = true) }
            .getOrDefault(false)

    const val ROTATION_SCHEME = "webfront"

    private const val AUTH_TOKEN = "auth_token"
    private const val DEVICE_ID = "device_id"
    private const val WEBVIEW = "webview"
    private const val NATIVE = "native"
    private const val RESET = "reset"
    private const val NATIVE_EKYC = "use_native_ekyc_api"
    private const val APP_VERSION = "app_version"
    private const val RETURN_URL = "return_url"
}
