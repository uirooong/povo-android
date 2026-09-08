package jp.povo.manager.ui.web

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
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.data.AccountRepository
import kotlinx.coroutines.launch

/** What [PovoWebScreen] needs, once it has been gathered. */
private data class WebTarget(
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
fun PovoWebHost(accountId: String, kind: PovoWebPageKind, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { AccountRepository.get(context) }
    val target by produceState<WebTarget?>(initialValue = null, accountId, kind) {
        val page = repo.webPage(accountId, kind)
        value = WebTarget(
            url = page?.link,
            exitPath = page?.exitUrl,
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
                    "${kind.label}のページがまだ取得できていません。" +
                        "アカウントを更新してからもう一度お試しください。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> PovoWebScreen(
                title = kind.label,
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

/**
 * What to call each page on screen.
 *
 * Kept here rather than on the enum because two of the three tiles arrive with
 * no usable label of their own — 契約管理 carries no `title` at all — so these
 * are this app's words, matching what the official app shows.
 */
val PovoWebPageKind.label: String
    get() = when (this) {
        PovoWebPageKind.EMAIL -> "メールアドレスの変更"
        PovoWebPageKind.PAYMENT -> "お支払い方法"
        PovoWebPageKind.CONTRACT -> "契約管理"
    }

/** One line saying what the page is for, for the list of entry points. */
val PovoWebPageKind.description: String
    get() = when (this) {
        PovoWebPageKind.EMAIL -> "povo に登録しているメールアドレスを変更します"
        PovoWebPageKind.PAYMENT -> "クレジットカードなどのお支払い方法を変更します"
        PovoWebPageKind.CONTRACT -> "プラン詳細の確認、SIM のお手続き、解約や MNP"
    }
