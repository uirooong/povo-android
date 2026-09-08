package jp.povo.manager.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import androidx.core.net.toUri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import java.util.concurrent.atomic.AtomicReference

/**
 * Hosts one of povo's own account pages — the payment method, the email
 * address, contract management.
 *
 * All three are web views by povo's design, and that is the point rather than a
 * shortcut: card numbers and MNP paperwork are handled on `shop.povo.jp`, so
 * this app never sees them and has no form of its own to get wrong. Each page
 * is the same one the official app opens. All three are marked `needs_xauth`,
 * so each is given the account's token — without it the page loads but shows
 * nobody signed in. A page that does not ask for it is not given one.
 *
 * The session travels in the **query string of the initial request** — see
 * [PovoWebUrl], which assembles the same parameter set the official app does.
 * A token in a URL is not ideal, but it is the only thing these pages accept,
 * and the URL never leaves this WebView.
 *
 * [PovoWebBridge] is installed before the load because the page probes for the
 * bridge during boot and treats its absence as an unsupported device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PovoWebScreen(
    title: String,
    url: String,
    authToken: String?,
    deviceId: String?,
    exitPath: String?,
    /**
     * The action's own `needs_xauth`. False withholds the session from both
     * channels — it is left out of the URL and the bridge has no token to hand
     * over — which is what the official app does for such a page.
     */
    needsXauth: Boolean,
    onRotatedToken: (String) -> Unit,
    onDone: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var signedOut by remember { mutableStateOf(false) }
    // Read by the bridge to decide whether the caller may have the token, so
    // it tracks the page actually on screen rather than the one we asked for.
    val currentUrl = remember { AtomicReference(url) }

    // Back should walk the page's own history first; leaving the screen on the
    // first Back would abandon a part-finished change.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.Close, contentDescription = "閉じる")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val target = remember(url, authToken, deviceId, exitPath, needsXauth) {
                PovoWebUrl.build(url, authToken, deviceId, exitPath, needsXauth)
            }

            if (!isPovoUrl(url)) {
                // Refuses to render a link that is not povo's. The URL is
                // stored from a server response, so this is the check that
                // stops a changed payload pointing the token-bearing view
                // somewhere else.
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "このリンクは povo のドメインではないため開けません。\n$url",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                return@Column
            }

            if (signedOut) {
                // The page bounced to its own login and renders nothing
                // further, so saying so beats the spinner it would otherwise
                // end on. Reachable if the token or device id no longer
                // satisfies the service — see docs/POVO-WEBVIEW-FINDINGS.md
                // for what the page requires.
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "povo 側のログインが求められたため、この画面からは操作できません。\n" +
                            "アカウントを更新してから開き直すか、公式アプリでお手続きください。",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        configure()
                        addJavascriptInterface(
                            PovoWebBridge(this, authToken.takeIf { needsXauth }) { currentUrl.get() },
                            PovoWebBridge.NAME,
                        )
                        webViewClient = ExitWatchingClient(
                            exitPath = exitPath,
                            onLoadingChange = { loading = it },
                            onNavigate = { currentUrl.set(it) },
                            onSignedOut = { signedOut = true },
                            onRotatedToken = onRotatedToken,
                            onExit = onDone,
                        )
                        // No extra headers: the official app issues a plain
                        // load and puts everything in the query string.
                        loadUrl(target)
                    }
                },
                // Released explicitly: a WebView outlives the composition
                // otherwise and keeps the page — and its session — alive.
                onRelease = { it.destroy() },
            )
        }
    }
}

/**
 * @SuppressLint: JavaScript is required — the page is a single-page app and
 * renders nothing without it.
 *
 * A javascript interface *is* installed, because the page probes for one and
 * treats its absence as an unsupported device ([PovoWebBridge]). The mitigation
 * is its size: two methods, one returning the app version and one the token,
 * and the latter refuses callers that are not on a povo host. Beyond that, file
 * and content access are off, so the page cannot read local files.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mediaPlaybackRequiresUserGesture = true
    // Makes the page's own viewport meta authoritative, which is the right
    // default for third-party responsive content. Note this does *not* fix the
    // overlapping layout seen on an old WebView: that is the engine's age —
    // flexbox `gap` only shipped in Chromium 84, and a WebView older than that
    // collapses gap-spaced rows into overlapping elements. Measured 0px on an
    // emulator running Chromium 83; devices get WebView from Play and are
    // current, so this is not something the app can or should work around.
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}

/**
 * Closes the screen once the page reaches its declared success path.
 *
 * That path is the only completion signal the payload offers — there is no
 * callback and no status endpoint for it — so the flow is considered finished
 * when the page navigates there.
 */
private class ExitWatchingClient(
    private val exitPath: String?,
    private val onLoadingChange: (Boolean) -> Unit,
    private val onNavigate: (String) -> Unit,
    private val onSignedOut: () -> Unit,
    private val onRotatedToken: (String) -> Unit,
    private val onExit: () -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        // Token rotation comes as a navigation to a scheme no WebView can
        // resolve, so it has to be swallowed here or it becomes a load error.
        PovoWebUrl.rotatedToken(target)?.let(onRotatedToken)
        if (PovoWebUrl.isRotation(target)) return true
        if (isExit(target)) {
            onExit()
            return true
        }
        onNavigate(target)
        if (isSignedOut(target)) onSignedOut()
        return false
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onLoadingChange(true)
        onNavigate(url)
        // Also checked here: a single-page app can reach the success route by
        // rewriting history rather than by a navigation the override sees.
        if (isExit(url)) onExit()
        if (isSignedOut(url)) onSignedOut()
    }

    override fun onPageFinished(view: WebView, url: String) {
        onNavigate(url)
        onLoadingChange(false)
    }

    /**
     * Whether the page has given up and gone to its own login or logout route.
     *
     * `/logged_out` is where the page's webview branch sends a signed-out user
     * and it renders nothing further, so without this the screen ends on a
     * spinner with no explanation.
     */
    private fun isSignedOut(url: String): Boolean {
        val path = runCatching { url.toUri().path }.getOrNull() ?: return false
        return SIGNED_OUT_PATHS.any { path.startsWith(it) }
    }

    private fun isExit(url: String): Boolean {
        val path = exitPath?.takeIf(String::isNotBlank) ?: return false
        return runCatching { url.toUri().path?.contains(path.trimStart('/')) == true }
            .onFailure { Log.w(TAG, "could not read $url", it) }
            .getOrDefault(false)
    }
}

/**
 * Whether a URL belongs to povo, host-suffix matched.
 *
 * `endsWith(".povo.jp")` plus the bare apex, rather than `contains("povo.jp")`
 * — the latter would accept `povo.jp.example.com`.
 */
internal fun isPovoUrl(url: String): Boolean {
    val uri = runCatching { url.toUri() }.getOrNull() ?: return false
    if (uri.scheme?.lowercase() != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return host == POVO_DOMAIN || host.endsWith(".$POVO_DOMAIN")
}

private const val POVO_DOMAIN = "povo.jp"
private val SIGNED_OUT_PATHS = listOf("/web/login", "/logged_out")
private const val TAG = "PovoPaymentWeb"
