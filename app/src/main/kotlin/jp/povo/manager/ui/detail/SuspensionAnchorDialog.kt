package jp.povo.manager.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import jp.povo.manager.core.model.Suspension
import jp.povo.manager.data.SuspensionAnchor
import jp.povo.manager.ui.common.formatIsoDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Which way the reader is describing how long the topping lasts. */
private enum class PeriodMode(val label: String) { DAYS("日数で"), DATE("日付で") }

/**
 * Collects the anchor for the 180-day countdown.
 *
 * Two ways in, because povo shows the same fact two ways and neither is
 * obviously the one to hand: the order history names the product
 * (`データ追加120GB（365日間）`) while the plan screen shows the expiry date. Making
 * someone convert between them is where a wrong date comes from.
 *
 * Whichever is used, the resulting expiry and suspension date are shown as they
 * type. That preview is the real check on the input — a typo in a year is
 * invisible in a text field and obvious in "残り 4000 日".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuspensionAnchorDialog(
    existing: SuspensionAnchor?,
    onDismiss: () -> Unit,
    onSave: (SuspensionAnchor) -> Unit,
    onClear: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    var purchase by rememberSaveable { mutableStateOf(existing?.purchaseDate ?: today.toString()) }
    var mode by rememberSaveable {
        // Reopens the way it was filled in; a first visit starts on days, which
        // is the number povo prints in the product's own name.
        mutableStateOf(
            if (existing == null || existing.durationDays != null) {
                PeriodMode.DAYS
            } else {
                PeriodMode.DATE
            },
        )
    }
    var daysText by rememberSaveable { mutableStateOf(existing?.durationDays?.toString() ?: "") }
    var expiryText by rememberSaveable { mutableStateOf(existing?.expiryDate ?: "") }
    var picking by rememberSaveable { mutableStateOf<Field?>(null) }

    val duration = daysText.trim().toIntOrNull()
    val resolvedExpiry = when (mode) {
        PeriodMode.DAYS -> duration?.let { Suspension.expiryFrom(purchase, it) }
        PeriodMode.DATE -> expiryText.takeIf { Suspension.parseDate(it) != null }
    }
    val forecast = resolvedExpiry?.let { Suspension.forecast(it, today) }
    // The expiry cannot precede the purchase; that is the one wrong combination
    // the preview alone would render as a plausible-looking date in the past.
    val ordered = forecast != null &&
        Suspension.parseDate(purchase)?.let { !forecast.expiry.isBefore(it) } == true
    val valid = ordered && (mode == PeriodMode.DATE || duration in 1..MAX_DURATION_DAYS)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("利用停止の起算日") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "最後に購入した有料トッピングを入力してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DateField(
                    label = "最終購入日",
                    value = formatIsoDate(purchase),
                    onClick = { picking = Field.PURCHASE },
                )

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PeriodMode.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = mode == entry,
                            onClick = { mode = entry },
                            shape = SegmentedButtonDefaults.itemShape(index, PeriodMode.entries.size),
                        ) { Text(entry.label) }
                    }
                }

                when (mode) {
                    PeriodMode.DAYS -> OutlinedTextField(
                        value = daysText,
                        onValueChange = { daysText = it.filter(Char::isDigit).take(4) },
                        label = { Text("有効期間") },
                        placeholder = { Text("365") },
                        suffix = { Text("日間") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = daysText.isNotBlank() && duration !in 1..MAX_DURATION_DAYS,
                        supportingText = {
                            Text("商品名の「（365日間）」の数字をそのまま入力できます")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PeriodMode.DATE -> DateField(
                        label = "有効期限",
                        value = expiryText.ifBlank { "未選択" }.let(::formatIsoDate),
                        onClick = { picking = Field.EXPIRY },
                    )
                }

                Preview(
                    expiry = resolvedExpiry,
                    forecast = forecast,
                    ordered = ordered,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val expiry = resolvedExpiry ?: return@TextButton
                    onSave(
                        SuspensionAnchor(
                            purchaseDate = purchase,
                            durationDays = duration.takeIf { mode == PeriodMode.DAYS },
                            expiryDate = expiry,
                            // Deliberately dropped: a new anchor is a new
                            // suspension date, and the reader has not been
                            // warned about that one yet.
                            notifiedFor = null,
                        ),
                    )
                },
            ) { Text("保存", fontWeight = FontWeight.Medium) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (existing != null) {
                    TextButton(onClick = onClear) {
                        Text("削除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("やめる") }
            }
        },
    )

    picking?.let { field ->
        val initial = when (field) {
            Field.PURCHASE -> Suspension.parseDate(purchase)
            Field.EXPIRY -> Suspension.parseDate(expiryText)
        } ?: today
        DatePickerSheet(
            initial = initial,
            onDismiss = { picking = null },
            onPick = { picked ->
                when (field) {
                    Field.PURCHASE -> purchase = picked.toString()
                    Field.EXPIRY -> expiryText = picked.toString()
                }
                picking = null
            },
        )
    }
}

private enum class Field { PURCHASE, EXPIRY }

@Composable
private fun DateField(label: String, value: String, onClick: () -> Unit) {
    // A read-only text field rather than an editable one: dates typed by hand
    // arrive in every format a person can imagine, and none of them parse.
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun Preview(expiry: String?, forecast: jp.povo.manager.core.model.SuspensionForecast?, ordered: Boolean) {
    val body = when {
        forecast == null -> "入力すると、有効期限と利用停止の予定日を計算します。"
        !ordered -> "有効期限が購入日より前になっています。"
        else -> null
    }
    if (body != null) {
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = if (ordered) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        return
    }
    requireNotNull(forecast)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PreviewRow("有効期限", formatIsoDate(expiry.orEmpty()))
        PreviewRow("停止予定日", formatIsoDate(forecast.suspendsOn.toString()))
        Text(
            if (forecast.overdue) {
                "この内容だと、すでに停止予定日を過ぎています。"
            } else {
                "この内容だと、残り ${forecast.daysLeft} 日です。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewRow(label: String, value: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDate,
    onDismiss: () -> Unit,
    onPick: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        // The picker works in UTC millis; anchoring at UTC midnight and reading
        // it back the same way keeps the day the reader tapped.
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                },
            ) { Text("決定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    ) {
        DatePicker(state = state)
    }
}

/** Ten years — longer than any topping povo sells, short enough to catch a typo. */
private const val MAX_DURATION_DAYS = 3650
