package jp.povo.manager.ui.store

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.povo.manager.core.model.ToppingProduct
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.ui.web.ThreeDsScreen

/**
 * povo's topping catalogue, and buying one.
 *
 * The prices and wording are the service's own strings, rendered verbatim —
 * this screen must never quote a figure povo did not.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToppingStoreScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ToppingStoreViewModel = viewModel(
        key = accountId,
        factory = remember(accountId) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ToppingStoreViewModel(
                        context.applicationContext as Application,
                        accountId,
                    ) as T
            }
        },
    )

    val state by vm.state.collectAsStateWithLifecycle()
    val purchase by vm.purchase.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The challenge takes over the screen: it is the card issuer's page and
    // has to be the only thing the buyer is looking at.
    (purchase as? PurchaseState.Authorising)?.let { authorising ->
        ThreeDsScreen(
            challengeUrl = authorising.order.challengeUrl.orEmpty(),
            returnUrl = AccountRepository.PURCHASE_REDIRECT_URL,
            onAuthorised = { vm.authorised(authorising.product) },
            onCancel = vm::abandoned,
        )
        return
    }

    LaunchedEffect(purchase) {
        when (val p = purchase) {
            is PurchaseState.Done -> {
                snackbar.showSnackbar("${p.product.name} を購入しました")
                vm.dismissPurchase()
            }

            is PurchaseState.Failed -> {
                snackbar.showSnackbar(p.message)
                vm.dismissPurchase()
            }

            else -> Unit
        }
    }

    when (val p = purchase) {
        is PurchaseState.Confirming -> ConfirmDialog(
            product = p.product,
            onConfirm = { vm.confirm(p.product) },
            onDismiss = vm::dismissPurchase,
        )

        is PurchaseState.Placing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("購入手続き中") },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(p.product.name)
                }
            },
            confirmButton = {},
        )

        else -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("トッピングを購入") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            state.error?.let { message ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = vm::load) { Text("再試行") }
                    }
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.sections.forEachIndexed { index, section ->
                    section.title?.let { title ->
                        item(key = "h-$index-$title") {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    // Keyed by position, not title: the same product appears in
                    // several sections (a promoted one is repeated under its own
                    // category), and two sections sharing a title — or both
                    // having none — would collide and take the list down.
                    items(section.products, key = { "$index-${it.id}" }) { product ->
                        ProductRow(product) { vm.select(product) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductRow(product: ToppingProduct, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                product.validity?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            product.price?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * The step where money starts moving, so it says the price and nothing else
 * competes with it.
 *
 * The official app makes this a swipe. An explicit, separately-worded button is
 * the same idea: a purchase should not be one stray tap away from a list row.
 */
@Composable
private fun ConfirmDialog(
    product: ToppingProduct,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("購入の確認") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(product.name, style = MaterialTheme.typography.bodyLarge)
                product.validity?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                product.price?.let {
                    Text(it, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    "登録済みのお支払い方法に請求されます。" +
                        if (product.requires3ds) "カード会社の認証画面が表示されます。" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("購入する", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
