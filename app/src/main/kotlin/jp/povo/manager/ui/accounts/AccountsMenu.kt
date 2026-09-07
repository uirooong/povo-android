package jp.povo.manager.ui.accounts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jp.povo.manager.BuildConfig

/**
 * Overflow menu for the account list.
 *
 * Settings are always here. The protocol spike is not: it is the tool that
 * established how these endpoints actually behave, and since they change
 * without notice it stays in the project rather than being deleted once the app
 * worked — but it has no business shipping to anyone, so the entry point is
 * compiled out of release builds along with the screen itself (see PovoNavHost).
 */
@Composable
fun AccountsOverflowMenu(onOpenSettings: () -> Unit, onOpenSpike: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Default.MoreVert, contentDescription = "その他")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("アプリの設定") },
            onClick = {
                expanded = false
                onOpenSettings()
            },
        )
        if (BuildConfig.DEBUG) {
            DropdownMenuItem(
                text = { Text("プロトコル検証 (開発用)") },
                onClick = {
                    expanded = false
                    onOpenSpike()
                },
            )
        }
    }
}
