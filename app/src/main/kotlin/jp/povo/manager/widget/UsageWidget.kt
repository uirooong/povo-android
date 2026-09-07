package jp.povo.manager.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import jp.povo.manager.MainActivity
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.data.AccountRepository

/** One line of the widget. */
internal data class WidgetRow(
    val accountId: String,
    val label: String,
    val remaining: String,
)

/**
 * Home-screen summary of every account's remaining data.
 *
 * Reads only from Room and never calls the API itself. The launcher can ask a
 * widget to redraw at any moment, and letting that trigger network calls would
 * mean unpredictable, unthrottled traffic against someone else's service;
 * [jp.povo.manager.work.RefreshWorker] owns fetching and pokes the widget once
 * it has new data.
 */
class UsageWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = AccountRepository.get(context)
        val accounts = repo.accounts()
        val usage = repo.usageSnapshot()

        val rows = accounts.map { account ->
            WidgetRow(
                accountId = account.id,
                label = account.label ?: account.id,
                // An account that has never been fetched gets a dash, not a
                // zero: "0 KB" would read as "you are out of data".
                remaining = usage[account.id]
                    ?.let { PlanUsage.formatKb(it.totalLeftKb) }
                    ?: "—",
            )
        }

        // A widget cannot be inspected from the app, and a stalled Glance
        // session is indistinguishable from a slow one, so leave a trace of
        // what was actually rendered.
        Log.i(TAG, "rendering ${rows.size} row(s)")
        provideContent {
            GlanceTheme { UsageWidgetContent(rows) }
        }
    }

    companion object {
        private const val TAG = "PovoWidget"

        /**
         * Redraws every placed widget.
         *
         * Safe to call when no widget is placed — it is then a no-op — so
         * callers do not have to check first.
         */
        suspend fun refresh(context: Context) {
            runCatching { UsageWidget().updateAll(context) }
                .onFailure { Log.w(TAG, "widget update failed", it) }
        }
    }
}

@Composable
internal fun UsageWidgetContent(rows: List<WidgetRow>) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            "povo データ残量",
            style = TextStyle(
                fontWeight = FontWeight.Medium,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
        )
        Spacer(GlanceModifier.height(6.dp))

        if (rows.isEmpty()) {
            Text(
                "アカウント未登録",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant),
            )
            return@Column
        }

        rows.forEach { row ->
            Row(
                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    row.label,
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                    modifier = GlanceModifier.defaultWeight(),
                    maxLines = 1,
                )
                Text(
                    row.remaining,
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = GlanceTheme.colors.primary,
                    ),
                )
            }
        }
    }
}

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()
}
