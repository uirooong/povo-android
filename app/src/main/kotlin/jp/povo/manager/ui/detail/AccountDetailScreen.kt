package jp.povo.manager.ui.detail

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.povo.manager.core.model.BillStatus
import jp.povo.manager.core.model.Money
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.core.model.Purchase
import jp.povo.manager.core.model.Topping
import jp.povo.manager.data.db.BillEntity
import jp.povo.manager.ui.common.formatDate
import jp.povo.manager.ui.web.description
import jp.povo.manager.ui.web.label
import jp.povo.manager.ui.common.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    accountId: String,
    onBack: () -> Unit,
    onOpenWebPage: (PovoWebPageKind) -> Unit = {},
) {
    val context = LocalContext.current
    val vm: AccountDetailViewModel = viewModel(
        key = accountId,
        factory = remember(accountId) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AccountDetailViewModel(
                        context.applicationContext as Application,
                        accountId,
                    ) as T
            }
        },
    )

    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pdf by vm.pdf.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.dismissMessage() }
    }

    pdf?.let { ready ->
        AlertDialog(
            onDismissRequest = vm::consumePdf,
            title = { Text("請求書 PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("この PDF はパスワードで保護されています。ビューアで開いたら以下を入力してください。")
                    Text(
                        ready.password ?: "生年月日が取得できていません",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    ready.password?.let {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(it)) }) {
                            Text("パスワードをコピー")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(ready.intent)
                    vm.consumePdf()
                }) { Text("開く") }
            },
            dismissButton = { TextButton(onClick = vm::consumePdf) { Text("閉じる") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("アカウントを削除") },
            text = { Text("${state.account?.label ?: accountId} をこのアプリから削除します。povo 側の契約には影響しません。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.remove(onBack)
                }) { Text("削除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("やめる") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.account?.label ?: accountId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh, enabled = !busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "更新")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "削除")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { UsageCard(state) }

                if (state.toppings.isNotEmpty()) {
                    item { Text("契約中のトッピング", style = MaterialTheme.typography.titleMedium) }
                    items(state.toppings, key = { it.name }) { ToppingCard(it) }
                }

                item { ProfileCard(state) }

                if (state.webPages.isNotEmpty()) {
                    item {
                        Text("povo のページ", style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.webPages, key = { it.name }) { kind ->
                        WebPageRow(kind) { onOpenWebPage(kind) }
                    }
                }

                if (state.bills.isNotEmpty()) {
                    item {
                        Text("請求", style = MaterialTheme.typography.titleMedium)
                    }
                    items(state.bills, key = { "${it.accountId}-${it.rowKey}" }) { bill ->
                        BillRow(bill) { bill.billId?.let(vm::openBillPdf) }
                    }
                }

                if (state.purchases.isNotEmpty()) {
                    item { Text("購入履歴", style = MaterialTheme.typography.titleMedium) }
                    items(
                        state.purchases,
                        key = { it.orderId ?: it.hashCode().toString() },
                    ) { PurchaseRow(it) }
                }
            }
        }
    }
}

@Composable
private fun UsageCard(state: DetailState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("データ残量", style = MaterialTheme.typography.titleMedium)
            Text(
                state.usage?.let { PlanUsage.formatKb(it.totalLeftKb) } ?: "未取得",
                style = MaterialTheme.typography.displaySmall,
            )
            state.usage?.let {
                Text(
                    "使用 ${PlanUsage.formatKb(it.totalUsedKb)}  ・  ${relativeTime(it.fetchedAt)}に取得",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (state.buckets.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                // All five pots are listed, including the empty ones: which pot
                // holds the data depends on the contract, and seeing the zeroes
                // is what makes that legible.
                state.buckets.forEach { bucket ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bucket.kind.label, Modifier.weight(1f))
                        Text(
                            PlanUsage.formatKb(bucket.leftKb),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (bucket.isEmpty) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }

            state.usage?.promotionLine2?.let {
                Text(
                    "${state.usage?.promotionLine1.orEmpty()} $it".trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(state: DetailState) {
    val account = state.account ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("契約情報", style = MaterialTheme.typography.titleMedium)
            InfoRow("名義", account.customerName)
            InfoRow("電話番号", account.phoneNo)
            InfoRow("プラン", account.planName)
            InfoRow("状態", account.status)
            InfoRow("開通日", account.activationDate)
            InfoRow("お支払い方法", account.paymentMasked)
            InfoRow("メール", account.email)
            InfoRow("回線番号 (SIN)", account.sin)
        }
    }
}

/**
 * A way into one of povo's own pages.
 *
 * These are handling steps this app deliberately does not reimplement — a card
 * number, an email change, an MNP request — so the row opens povo's page for it
 * signed in, rather than a form of our own.
 */
@Composable
private fun WebPageRow(kind: PovoWebPageKind, onOpen: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                when (kind) {
                    PovoWebPageKind.EMAIL -> Icons.Default.MailOutline
                    PovoWebPageKind.PAYMENT -> Icons.Default.CreditCard
                    PovoWebPageKind.CONTRACT -> Icons.Default.Description
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    kind.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BillRow(bill: BillEntity, onOpenPdf: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(bill.title ?: "(無題)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(
                        statusLabel(bill.status),
                        bill.timeEpochMillis?.let(::formatDate),
                    ).joinToString(" ・ "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            bill.amountValue?.let {
                Text(
                    Money(value = it, prefix = bill.amountPrefix).format(),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (bill.hasPdf && bill.billId != null) {
                TextButton(onClick = onOpenPdf) { Text("PDF") }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (runCatching { BillStatus.valueOf(status) }.getOrNull()) {
    BillStatus.PAID -> "支払い済み"
    BillStatus.UNPAID -> "未払い"
    BillStatus.UPCOMING -> "請求予定"
    BillStatus.INSTANT -> "都度課金"
    else -> "—"
}

@Composable
private fun ToppingCard(topping: Topping) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(topping.name, style = MaterialTheme.typography.bodyLarge)
            // These strings arrive already formatted by the service; rendering
            // them verbatim keeps the app in step with the official one.
            topping.remaining?.let {
                Text("残量 $it", style = MaterialTheme.typography.bodyMedium)
            }
            topping.expiryDate?.let {
                Text(
                    listOfNotNull(it, topping.expiryRemaining).joinToString("  ・  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PurchaseRow(purchase: Purchase) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(purchase.title ?: "(不明な商品)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    listOfNotNull(purchase.status, purchase.purchaseDate).joinToString(" ・ "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (purchase.succeeded) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            purchase.price?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
