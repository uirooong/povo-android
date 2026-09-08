package jp.povo.manager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.povo.manager.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The app's own preferences.
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

    /** Whether to work out and show the 180-day countdown at all. */
    val suspensionEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[SUSPENSION_KEY] ?: true }

    suspend fun setSuspensionEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SUSPENSION_KEY] = enabled }
    }

    /**
     * Whether to raise a notification as the date approaches.
     *
     * Off by default. Showing a number on a screen someone opened is one thing;
     * interrupting them is a decision they should make themselves, and on
     * Android 13+ turning it on is what prompts for the permission.
     */
    val suspensionNotifyEnabled: Flow<Boolean> =
        context.settingsDataStore.data.map { it[SUSPENSION_NOTIFY_KEY] ?: false }

    suspend fun setSuspensionNotifyEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[SUSPENSION_NOTIFY_KEY] = enabled }
    }

    /** How many days of warning to give, clamped to [NOTIFY_DAYS_RANGE]. */
    val suspensionNotifyDays: Flow<Int> =
        context.settingsDataStore.data.map {
            (it[SUSPENSION_NOTIFY_DAYS_KEY] ?: DEFAULT_NOTIFY_DAYS).coerceIn(NOTIFY_DAYS_RANGE)
        }

    suspend fun setSuspensionNotifyDays(days: Int) {
        context.settingsDataStore.edit {
            it[SUSPENSION_NOTIFY_DAYS_KEY] = days.coerceIn(NOTIFY_DAYS_RANGE)
        }
    }

    companion object {
        const val DEFAULT_NOTIFY_DAYS = 14

        /**
         * The upper bound is the whole grace period: warning earlier than the
         * clock itself runs would fire the moment an anchor is saved.
         */
        val NOTIFY_DAYS_RANGE = 1..180

        private val THEME_KEY = stringPreferencesKey("theme_mode")
        private val SUSPENSION_KEY = booleanPreferencesKey("suspension_enabled")
        private val SUSPENSION_NOTIFY_KEY = booleanPreferencesKey("suspension_notify_enabled")
        private val SUSPENSION_NOTIFY_DAYS_KEY = intPreferencesKey("suspension_notify_days")
    }
}

/**
 * The one DataStore behind every preference in the app.
 *
 * `preferencesDataStore` may be declared once per file *and* once per name for
 * the process — a second delegate with this name throws at runtime — so
 * [SuspensionStore] shares this rather than opening its own.
 */
internal val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "povo-settings")
