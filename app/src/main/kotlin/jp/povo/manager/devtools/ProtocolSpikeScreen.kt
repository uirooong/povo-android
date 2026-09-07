package jp.povo.manager.devtools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Development-only screen for the Phase 1 protocol spike. See
 * [ProtocolSpikeViewModel] for what it is establishing.
 *
 * Everything lives in one [LazyColumn] — controls included — so the control
 * section can never push the buttons below it off-screen on a small display.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolSpikeScreen(vm: ProtocolSpikeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("プロトコル検証 (Phase 1)") },
                actions = {
                    TextButton(onClick = vm::exportLog) { Text("保存") }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(vm.logAsText()))
                    }) { Text("コピー") }
                    TextButton(onClick = vm::clearLog) { Text("クリア") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Controls(state, vm) }
                item { HorizontalDivider() }
                items(state.log.asReversed()) { entry ->
                    LogCard(entry) { clipboard.setText(AnnotatedString(entry.body)) }
                }
            }
        }
    }
}

@Composable
private fun Controls(state: ProtocolSpikeViewModel.State, vm: ProtocolSpikeViewModel) {
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.accounts.isNotEmpty()) {
            Text(
                "保存済みアカウント (${state.accounts.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            state.accounts.forEach { account ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilledTonalButton(
                        onClick = { vm.switchTo(account.accountId) },
                        enabled = !state.busy && account.accountId != state.activeAccountId,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            (if (account.accountId == state.activeAccountId) "● " else "") +
                                (account.label ?: account.accountId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { vm.removeAccount(account.accountId) }) { Text("削除") }
                }
            }
            Button(
                onClick = vm::fetchAllAccountsParallel,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("全アカウントを並列更新") }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "アカウントを追加",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = vm::addAccount, enabled = !state.busy) { Text("新規スロット") }
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            label = { Text("メールアドレス") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text("電話番号 (任意, 例 09012345678)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Wraps rather than ellipsizing: 32 hex characters do not fit one
            // line, and this is a value you need in full to reproduce a request
            // by hand. The button copies it for the same reason.
            Text(
                "device_id: ${state.deviceId}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(state.deviceId)) },
            ) { Text("コピー") }
            TextButton(onClick = vm::addAccount) { Text("再生成") }
        }

        if (state.requiredActions.isNotEmpty()) {
            Text(
                "サーバーが要求した認証: ${state.requiredActions.joinToString(" → ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Text("ログイン", style = MaterialTheme.typography.titleSmall)
        ButtonGrid(
            !state.busy,
            "1. login/action" to vm::step1RequestLoginAction,
            "2. メールOTP送信" to vm::step2SendEmailOtp,
            "2. SMS OTP送信" to vm::step2SendMobileOtp,
        )

        OutlinedTextField(
            value = state.otp,
            onValueChange = vm::onOtpChange,
            label = { Text("OTP コード") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = vm::step3Login,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("3. ログイン (users/auth)") }

        Text(
            when {
                state.restored -> "データ取得 (保存済みセッションを復元)"
                state.loggedIn -> "データ取得 (ログイン済み)"
                else -> "データ取得 (要ログイン)"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        Button(
            onClick = vm::fetchEverything,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("全エンドポイントを一括取得") }

        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("任意エンドポイント", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = state.customPath,
            onValueChange = vm::onCustomPathChange,
            label = { Text("path (/ 始まりは絶対パス。例: /v4/jp/en/mobile/layout/profile/info)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = state.customPrefix,
                onValueChange = vm::onCustomPrefixChange,
                label = { Text("prefix (空=なし)") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = state.customVersion,
                onValueChange = vm::onCustomVersionChange,
                label = { Text("version") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = state.customBody,
            onValueChange = vm::onCustomBodyChange,
            label = { Text("body JSON (POST 用。空なら {} を送る)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = vm::fetchCustom,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("GET") }
            Button(
                onClick = vm::postCustom,
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) { Text("POST") }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        ButtonGrid(
            !state.busy,
            "プロフィール" to vm::fetchProfile,
            "データ残量" to vm::fetchPlanUsage,
            "プラン詳細" to vm::fetchPlanDetails,
            "利用明細" to vm::fetchUsageDataDetails,
            "請求" to vm::fetchBills,
            "請求PDF" to vm::fetchBillPdf,
            "トッピング(quilt)" to vm::fetchQuiltPlan,
            "quilt home" to vm::fetchQuiltHome,
            "購入履歴(quilt)" to vm::fetchQuiltOrderHistory,
            "購入履歴(dashboard)" to vm::fetchTelcoDashboard,
            "紹介コード" to vm::fetchReferral,
            "トークン更新" to vm::refreshToken,
        )
    }
}

@Composable
private fun ButtonGrid(enabled: Boolean, vararg actions: Pair<String, () -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        actions.toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (label, action) ->
                    FilledTonalButton(
                        onClick = action,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LogCard(entry: ProtocolSpikeViewModel.Entry, onCopy: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (entry.ok) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[${entry.at}] ${entry.label}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCopy) { Text("コピー") }
            }
            Text(
                entry.body,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
            )
        }
    }
}
