package jp.povo.manager.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.povo.manager.data.SettingsStore

/**
 * The 180-day countdown's own settings.
 *
 * Two switches rather than one because they are different decisions: showing a
 * number on a screen someone chose to open costs them nothing, while a
 * notification interrupts. The second is also what triggers the Android 13+
 * permission prompt, so it has to be a deliberate act.
 */
@Composable
fun SuspensionSettingsCard(
    enabled: Boolean,
    notifyEnabled: Boolean,
    notifyDays: Int,
    onEnabledChange: (Boolean) -> Unit,
    onNotifyEnabledChange: (Boolean) -> Unit,
    onNotifyDaysChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    var editingDays by rememberSaveable { mutableStateOf(false) }

    // Asked for at the moment it becomes meaningful rather than at startup: a
    // permission prompt with no explanation is the one people refuse.
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        // Left off when refused, so the switch never claims a notification
        // that cannot arrive.
        onNotifyEnabledChange(granted)
    }

    val alreadyPermitted = remember(context) {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("利用停止までの日数", style = MaterialTheme.typography.titleMedium)

            SwitchRow(
                label = "残り日数を表示",
                checked = enabled,
                onCheckedChange = onEnabledChange,
            )
            SwitchRow(
                label = "残りわずかになったら通知",
                checked = notifyEnabled,
                enabled = enabled,
                onCheckedChange = { wanted ->
                    if (wanted && !alreadyPermitted) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onNotifyEnabledChange(wanted)
                    }
                },
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled && notifyEnabled) { editingDays = true }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "通知する残り日数",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled && notifyEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "$notifyDays 日前",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                "povo2.0 は、最後に期限が切れたトッピングの翌日から 180 日間" +
                    "有料トッピングの購入がないと、順次利用停止になります。" +
                    "起算日は回線ごとに詳細画面から登録してください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editingDays) {
        NotifyDaysDialog(
            current = notifyDays,
            onDismiss = { editingDays = false },
            onConfirm = {
                onNotifyDaysChange(it)
                editingDays = false
            },
        )
    }
}

/**
 * The whole row is the target, not the switch.
 *
 * Matches the radio rows above it, and `Role.Switch` with a null
 * `onCheckedChange` is what stops the control being announced as a second,
 * separate target to a screen reader.
 */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NotifyDaysDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val days = text.trim().toIntOrNull()
    val valid = days != null && days in SettingsStore.NOTIFY_DAYS_RANGE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通知する残り日数") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter(Char::isDigit).take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("日前") },
                    isError = text.isNotBlank() && !valid,
                )
                Text(
                    "${SettingsStore.NOTIFY_DAYS_RANGE.first}〜" +
                        "${SettingsStore.NOTIFY_DAYS_RANGE.last} の範囲で指定できます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { days?.let(onConfirm) }, enabled = valid) {
                Text("保存", fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
