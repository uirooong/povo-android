package jp.povo.manager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.povo.manager.data.SettingsStore
import jp.povo.manager.ui.PovoNavHost
import jp.povo.manager.ui.theme.PovoTheme
import jp.povo.manager.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    /**
     * Held in state rather than read straight from `intent`: the notification
     * launches with `FLAG_ACTIVITY_SINGLE_TOP`, so tapping one while the app is
     * already open delivers through [onNewIntent], where the original `intent`
     * is stale.
     */
    private var startAccountId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startAccountId = intent?.accountId()
        enableEdgeToEdge()
        setContent {
            // Read here rather than inside the theme so that the whole app —
            // including the widget's configuration activity — resolves its
            // colours from one stored value.
            val settings = remember { SettingsStore(applicationContext) }
            val mode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            PovoTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PovoNavHost(
                        startAccountId = startAccountId,
                        onStartAccountHandled = { startAccountId = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.accountId()?.let { startAccountId = it }
    }

    private fun Intent.accountId(): String? =
        getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.isNotBlank() }

    companion object {
        /** Set by a notification to open straight to the account it is about. */
        const val EXTRA_ACCOUNT_ID = "jp.povo.manager.ACCOUNT_ID"
    }
}
