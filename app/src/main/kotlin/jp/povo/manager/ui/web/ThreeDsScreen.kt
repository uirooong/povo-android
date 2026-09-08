package jp.povo.manager.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri

/**
 * The card issuer's 3-D Secure challenge, shown so a purchase can be authorised.
 *
 * Deliberately not [PovoWebScreen]. That screen refuses to render anything off
 * povo's domain and installs a bridge that can hand over the account's token;
 * both are right for povo's own pages and wrong here. A 3-D Secure challenge
 * legitimately starts on the payment processor and hops to whichever bank
 * issued the card, so the host cannot be known in advance — and precisely
 * because of that, this screen carries **no javascript interface, no token and
 * no account identity**. It renders a URL the service itself handed back and
 * watches for the return.
 *
 * Nothing is charged until the challenge completes. Leaving early abandons the
 * order, which was confirmed against a live account: an abandoned order never
 * reached the purchase history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreeDsScreen(
    challengeUrl: String,
    /** The URL the challenge redirects to once the issuer approves. */
    returnUrl: String,
    onAuthorised: () -> Unit,
    onCancel: () -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Issuers routinely bounce through several pages; Back should walk them
    // rather than abandoning a challenge halfway.
    BackHandler(enabled = webView?.canGoBack() == true) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("お支払いの認証") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "中止")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                "カード会社の認証ページです。完了するまで購入は確定しません。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        configureForChallenge()
                        webViewClient = ReturnWatchingClient(
                            returnUrl = returnUrl,
                            onLoadingChange = { loading = it },
                            onReturn = onAuthorised,
                        )
                        loadUrl(challengeUrl)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

/**
 * @SuppressLint: 3-D Secure pages are built on scripted forms and render
 * nothing without JavaScript.
 *
 * No `addJavascriptInterface` here, which is the point: the page is on somebody
 * else's domain, so it gets no channel back into the app. File and content
 * access are off as well.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureForChallenge() {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.useWideViewPort = true
    settings.loadWithOverviewMode = true
}

/**
 * Closes the challenge once the issuer sends the buyer back.
 *
 * Matched on path rather than on the whole URL: the processor appends its own
 * result parameters to the return address, so an equality check would never
 * fire.
 */
private class ReturnWatchingClient(
    returnUrl: String,
    private val onLoadingChange: (Boolean) -> Unit,
    private val onReturn: () -> Unit,
) : WebViewClient() {

    private val expected = runCatching { returnUrl.toUri() }.getOrNull()

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!isReturn(request.url.toString())) return false
        onReturn()
        return true
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onLoadingChange(true)
        if (isReturn(url)) onReturn()
    }

    override fun onPageFinished(view: WebView, url: String) = onLoadingChange(false)

    private fun isReturn(url: String): Boolean {
        val target = runCatching { url.toUri() }.getOrNull() ?: return false
        val want = expected ?: return false
        return target.host.equals(want.host, ignoreCase = true) && target.path == want.path
    }
}
