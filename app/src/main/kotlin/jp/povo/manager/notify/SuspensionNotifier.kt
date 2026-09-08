package jp.povo.manager.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import jp.povo.manager.MainActivity
import jp.povo.manager.R
import jp.povo.manager.core.model.SuspensionForecast
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tells the reader a line is approaching its 180-day suspension.
 *
 * One notification per account, replaced rather than stacked: the message is a
 * countdown, so a second copy of yesterday's number is noise. Whether one is
 * posted at all — and how many days ahead — is decided by the caller from
 * stored preferences; this only knows how to say it.
 */
object SuspensionNotifier {

    /**
     * Registered at startup rather than at first use.
     *
     * Channels are settings, and the reader can only find and adjust one that
     * exists. Creating it again is a no-op, so this is safe to call on every
     * launch.
     */
    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "利用停止の予告",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "有料トッピングを長期間購入していない回線をお知らせします"
        }
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    /**
     * Posts the warning for one account, or does nothing if it cannot.
     *
     * Returns whether it was actually posted, so the caller only records having
     * warned when the reader could have seen it — a notification dropped for a
     * missing permission must not suppress tomorrow's.
     */
    fun notify(
        context: Context,
        accountId: String,
        label: String,
        forecast: SuspensionForecast,
    ): Boolean {
        val manager = NotificationManagerCompat.from(context)
        // Checked here rather than in a helper: lint only recognises a guard in
        // the same function as the notify() call, and a suppression would mean
        // nobody notices when the guard stops being true.
        val permitted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!permitted || !manager.areNotificationsEnabled()) {
            Log.i(TAG, "not posting for $accountId: notifications are not permitted")
            return false
        }

        val date = forecast.suspendsOn.format(DATE)
        val title = if (forecast.overdue) {
            "$label の利用停止予定日を過ぎています"
        } else {
            "$label は利用停止まで残り ${forecast.daysLeft} 日"
        }
        val body = "$date 以降、順次利用停止の対象です。" +
            "有料トッピングを購入すると起算日がリセットされます。"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_povo)
            .setContentTitle(title)
            .setContentText(body)
            // The body runs past one line on most screens, and the date is the
            // part worth reading.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAccount(context, accountId))
            .build()

        return runCatching {
            manager.notify(idFor(accountId), notification)
            true
        }.onFailure { Log.w(TAG, "could not post for $accountId", it) }.getOrDefault(false)
    }

    /** Clears an account's warning — used when its anchor is changed or removed. */
    fun cancel(context: Context, accountId: String) {
        NotificationManagerCompat.from(context).cancel(idFor(accountId))
    }

    private fun openAccount(context: Context, accountId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_ACCOUNT_ID, accountId)
        }
        return PendingIntent.getActivity(
            context,
            idFor(accountId),
            intent,
            // Immutable per platform requirement; UPDATE_CURRENT so a re-posted
            // warning still opens the account rather than a stale extra.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * A stable id per account so a new warning replaces the old one.
     *
     * Account ids are UUIDs, so their hash is as good a spread as anything and
     * a collision would only mean two lines sharing one notification slot.
     */
    private fun idFor(accountId: String) = accountId.hashCode()

    private const val TAG = "PovoSuspension"
    private const val CHANNEL_ID = "povo-suspension"
    private val DATE = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN)
}
