package jp.povo.manager.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import uniffi.povo_core.ActionType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.step, state.addedAccountId) {
        val id = state.addedAccountId
        if (state.step == LoginViewModel.Step.DONE && id != null) onDone(id)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アカウントを追加") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

            state.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(12.dp)) {
                        Text(error, Modifier.weight(1f))
                        TextButton(onClick = vm::dismissError) { Text("閉じる") }
                    }
                }
            }

            when (state.step) {
                LoginViewModel.Step.IDENTIFIER -> IdentifierStep(state, vm)
                LoginViewModel.Step.OTP -> OtpStep(state, vm)
                LoginViewModel.Step.DONE -> Text("追加しました")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentifierStep(state: LoginViewModel.State, vm: LoginViewModel) {
    Text(
        "povo に登録しているメールアドレス、または電話番号を入力してください。" +
            "どの認証が必要かはサーバーが判断します。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = !state.useSms,
            onClick = { vm.onUseSmsChange(false) },
            shape = SegmentedButtonDefaults.itemShape(0, 2),
        ) { Text("メール") }
        SegmentedButton(
            selected = state.useSms,
            onClick = { vm.onUseSmsChange(true) },
            shape = SegmentedButtonDefaults.itemShape(1, 2),
        ) { Text("電話番号") }
    }

    if (state.useSms) {
        OutlinedTextField(
            value = state.phone,
            onValueChange = vm::onPhoneChange,
            label = { Text("電話番号 (例 09012345678)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        OutlinedTextField(
            value = state.email,
            onValueChange = vm::onEmailChange,
            label = { Text("メールアドレス") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Button(
        onClick = vm::submitIdentifier,
        enabled = state.canSubmitIdentifier,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("確認コードを送る") }
}

@Composable
private fun OtpStep(state: LoginViewModel.State, vm: LoginViewModel) {
    val destination = when (state.currentChallenge) {
        ActionType.EMAIL_OTP -> state.email
        else -> state.phone
    }
    val channel = if (state.currentChallenge == ActionType.EMAIL_OTP) "メール" else "SMS"

    Text("$destination に$channel で送った確認コードを入力してください。")

    // Only relevant when the server chained more than one challenge, which has
    // not been seen in practice but the API allows.
    if (state.pending.size + state.completed.size > 1) {
        Text(
            "認証 ${state.completed.size + 1} / ${state.completed.size + state.pending.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    OutlinedTextField(
        value = state.otp,
        onValueChange = vm::onOtpChange,
        label = { Text("確認コード") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )

    Button(
        onClick = vm::submitOtp,
        enabled = !state.busy && state.otp.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("ログイン") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = vm::resendOtp, enabled = !state.busy) { Text("コードを再送") }
        TextButton(onClick = vm::reset, enabled = !state.busy) { Text("やり直す") }
    }
}
