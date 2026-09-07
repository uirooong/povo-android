package jp.povo.manager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.povo.manager.data.SettingsStore
import jp.povo.manager.ui.PovoNavHost
import jp.povo.manager.ui.theme.PovoTheme
import jp.povo.manager.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Read here rather than inside the theme so that the whole app —
            // including the widget's configuration activity — resolves its
            // colours from one stored value.
            val settings = remember { SettingsStore(applicationContext) }
            val mode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            PovoTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PovoNavHost()
                }
            }
        }
    }
}
