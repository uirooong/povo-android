package jp.povo.manager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.povo.manager.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The app's own preferences — currently just the theme.
 *
 * Kept apart from [AccountRepository] and its Room cache on purpose: this
 * survives everything, whereas the cache is dropped on any schema change. It
 * holds nothing secret, so plain DataStore is right; credentials go through the
 * Keystore-backed session store instead.
 */
class SettingsStore(private val context: Context) {

    val themeMode: Flow<ThemeMode> =
        context.settingsDataStore.data.map { ThemeMode.from(it[THEME_KEY]) }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { it[THEME_KEY] = mode.name }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_mode")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "povo-settings")
