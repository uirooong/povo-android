package jp.povo.manager.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import jp.povo.manager.data.SettingsStore
import jp.povo.manager.ui.theme.PovoTheme
import jp.povo.manager.ui.theme.ThemeMode
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.data.db.AccountEntity
import kotlinx.coroutines.launch

/**
 * Configuration screen for [UsageDonutWidget]: pick which account it shows.
 *
 * The launcher starts this when the widget is placed, and the result decides
 * whether the placement goes through — so the activity must set
 * `RESULT_CANCELED` up front and only report success once a choice is stored.
 * Backing out therefore removes the half-configured widget instead of leaving a
 * blank one on the home screen.
 */
class AccountPickerActivity : ComponentActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cancelled unless a choice is actually made; see the class comment.
        setResult(Activity.RESULT_CANCELED, resultIntent())

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repo = AccountRepository.get(this)
        val settings = SettingsStore(applicationContext)
        setContent {
            val mode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            PovoTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val accounts by produceState(initialValue = emptyList<AccountEntity>()) {
                        value = repo.accounts()
                    }
                    val usage by produceState(initialValue = emptyMap<String, String>()) {
                        value = repo.usageSnapshot()
                            .mapValues { (_, snapshot) -> PlanUsage.formatKb(snapshot.totalLeftKb) }
                    }
                    PickerScreen(accounts, usage, ::choose)
                }
            }
        }
    }

    private fun choose(account: AccountEntity) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@AccountPickerActivity)
                .getGlanceIdBy(appWidgetId)
            updateAppWidgetState(this@AccountPickerActivity, glanceId) { prefs ->
                prefs[UsageDonutWidget.ACCOUNT_KEY] = account.id
            }
            // Draw immediately: the launcher does not send an update after a
            // successful configuration, so without this the widget would sit on
            // its loading layout until something else refreshed it.
            UsageDonutWidget().update(this@AccountPickerActivity, glanceId)

            setResult(Activity.RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    accounts: List<AccountEntity>,
    remainingByAccount: Map<String, String>,
    onPick: (AccountEntity) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("表示するアカウント") }) },
    ) { padding ->
        if (accounts.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "アカウントがまだありません。先にアプリでアカウントを追加してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(accounts, key = { it.id }) { account ->
                Card(onClick = { onPick(account) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            account.label ?: account.id,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                account.planName,
                                remainingByAccount[account.id]?.let { "残り $it" },
                            ).joinToString(" ・ ").ifBlank { "未取得" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
