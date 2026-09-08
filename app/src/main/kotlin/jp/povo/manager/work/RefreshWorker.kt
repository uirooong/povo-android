package jp.povo.manager.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.data.BillsPolicy
import jp.povo.manager.data.RefreshResult
import java.util.concurrent.TimeUnit

/**
 * Refreshes every account on a schedule so the list and the widget are current
 * when they are next looked at.
 *
 * The interval is [REFRESH_INTERVAL_MINUTES], which is WorkManager's own floor
 * for periodic work — chosen deliberately, and it is not free: every run costs
 * several requests per account against someone else's production service. What
 * keeps that in bounds is [AccountRepository.refreshAll], which caps
 * concurrency, staggers the accounts, and honours any `block_until` the service
 * sends back; and the backoff below, which is longer than the period on
 * purpose, so a rate-limited account backs away instead of retrying into the
 * same wall.
 */
class RefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = AccountRepository.get(applicationContext)
        // IF_STALE: the data allowance is what needs to be current every 15
        // minutes; the billing timeline and purchase history get picked up
        // about once an hour instead of on every run.
        val outcomes = repo.refreshAll(BillsPolicy.IF_STALE)
        if (outcomes.isEmpty()) return Result.success()
        // The widget is redrawn by AccountRepository.refreshAll itself, so
        // there is nothing to do here.

        // BLOCKED is deliberately not counted. It means the service told us to
        // back off until a time it chose, and `refresh` already skips such an
        // account without making a request — so retrying re-runs every *other*
        // account early for nothing, which is the opposite of honouring the
        // back-off.
        val transient = outcomes.count { it.result == RefreshResult.FAILED }
        Log.i(
            TAG,
            "refreshed ${outcomes.size} accounts: " +
                outcomes.groupingBy { it.result }.eachCount(),
        )

        // A stale login is not something a retry fixes — it needs the user — so
        // only genuinely transient failures ask WorkManager to back off and
        // try again.
        return if (transient > 0) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "PovoRefreshWorker"
        private const val UNIQUE_NAME = "povo-periodic-refresh"

        /** WorkManager rejects anything shorter than 15 minutes. */
        const val REFRESH_INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(
                REFRESH_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // UPDATE rather than KEEP: KEEP would ignore this request
                // outright on any install that already has the work enqueued,
                // so a changed interval would never reach an existing user.
                // UPDATE applies the new spec while keeping the original
                // enqueue time, so relaunching the app does not push the next
                // run out — which is what KEEP was there to prevent.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
