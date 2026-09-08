package jp.povo.manager.ui.accounts

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.ui.common.relativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onAddAccount: () -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenSpike: () -> Unit = {},
    vm: AccountsViewModel = viewModel(),
) {
    val cards by vm.cards.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("povo マネージャー") },
                actions = { AccountsOverflowMenu(onOpenSettings, onOpenSpike) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccount) {
                Icon(Icons.Default.Add, contentDescription = "アカウントを追加")
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = vm::refreshAll,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            if (cards.isEmpty()) {
                EmptyState(Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(cards, key = { it.account.id }) { card ->
                        AccountRow(card) { onOpenAccount(card.account.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text("アカウントがまだありません", style = MaterialTheme.typography.titleMedium)
            Text(
                "右下の + から povo アカウントを追加してください。複数登録すると一覧でまとめて更新できます。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountRow(card: AccountCard, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (card.needsLogin) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        card.account.label ?: card.account.id,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.account.planName?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    // Every allowance pot counts: a contract can show zero in
                    // `basic` while holding tens of gigabytes in `boost`.
                    card.usage?.let { PlanUsage.formatKb(it.totalLeftKb) } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            if (card.buckets.any { !it.isEmpty }) {
                Text(
                    card.buckets.filterNot { it.isEmpty }
                        .joinToString("  ") { "${it.kind.label} ${PlanUsage.formatKb(it.leftKb)}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val blocked = card.blockedUntil
            val suspension = card.suspension
            when {
                card.needsLogin -> Text(
                    "再ログインが必要です",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                blocked != null -> Text(
                    "サーバーの要求により ${relativeTime(blocked)} まで更新を停止中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                card.account.lastError != null -> Text(
                    card.account.lastError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                // Below the failures — a line that cannot refresh has a more
                // immediate problem than one that lapses in a fortnight.
                suspension != null -> Text(
                    if (suspension.overdue) {
                        "利用停止の予定日を過ぎています"
                    } else {
                        "利用停止まで残り ${suspension.daysLeft} 日"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    card.account.lastRefreshedAt?.let { "最終更新: ${relativeTime(it)}" }
                        ?: "未取得",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
