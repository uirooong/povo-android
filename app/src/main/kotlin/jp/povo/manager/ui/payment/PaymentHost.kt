package jp.povo.manager.ui.payment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.povo.manager.data.AccountRepository
import kotlinx.coroutines.launch

/** What [PaymentWebViewScreen] needs, once it has been gathered. */
private data class PaymentTarget(
    val url: String?,
    val exitPath: String?,
    val authToken: String?,
    /** The account's own device id; the page needs it beside the token. */
    val deviceId: String?,
)

/**
 * Gathers the page URL and a valid token before the web view opens.
 *
 * Both come from storage rather than from the caller: the URL is the server's
 * own (handed out with the profile page) and the token has to be renewed if it
 * expired, which is suspending work and so cannot happen during composition of
 * the screen itself.
 */
@Composable
fun PaymentHost(accountId: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { AccountRepository.get(context) }
    val target by produceState<PaymentTarget?>(initialValue = null, accountId) {
        val account = repo.accounts().firstOrNull { it.id == accountId }
        value = PaymentTarget(
            url = account?.paymentUpdateUrl,
            exitPath = account?.paymentExitUrl,
            authToken = repo.freshAuthToken(accountId),
            deviceId = repo.session(accountId)?.deviceId,
        )
    }

    when (val t = target) {
        null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> when (val url = t.url) {
            // The link only exists once a refresh has read the profile page, so
            // a freshly added account can reach this screen without one.
            null -> Box(
                Modifier.fillMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "お支払い方法の変更ページがまだ取得できていません。" +
                        "アカウントを更新してからもう一度お試しください。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> PaymentWebViewScreen(
                url = url,
                authToken = t.authToken,
                deviceId = t.deviceId,
                exitPath = t.exitPath,
                onRotatedToken = { token -> scope.launch { repo.updateAuthToken(accountId, token) } },
                onDone = onDone,
            )
        }
    }
}
