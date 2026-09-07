package jp.povo.manager.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN)
private val dateTimeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN)

/**
 * A human-scale description of when something happened.
 *
 * Used for "last updated", where the exact minute matters far less than
 * whether the reading is current — an account that failed to refresh should
 * read "3時間前", not a timestamp the reader has to date-subtract themselves.
 */
fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - epochMillis
    val future = delta < 0
    val seconds = abs(delta) / 1000
    val text = when {
        seconds < 60 -> "たった今"
        seconds < 3600 -> "${seconds / 60}分"
        seconds < 86_400 -> "${seconds / 3600}時間"
        seconds < 86_400 * 7 -> "${seconds / 86_400}日"
        else -> return dateFormat.format(Date(epochMillis))
    }
    if (text == "たった今") return text
    // Braces are required: Kotlin would otherwise read `後` as part of the name.
    return if (future) "${text}後" else "${text}前"
}

fun formatDate(epochMillis: Long): String = dateFormat.format(Date(epochMillis))

fun formatDateTime(epochMillis: Long): String = dateTimeFormat.format(Date(epochMillis))
