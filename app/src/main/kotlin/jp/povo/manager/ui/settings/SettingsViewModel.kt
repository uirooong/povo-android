package jp.povo.manager.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.BuildConfig
import jp.povo.manager.data.SettingsStore
import jp.povo.manager.update.AppUpdater
import jp.povo.manager.update.AvailableUpdate
import jp.povo.manager.update.UpdateCheck
import jp.povo.manager.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where the update flow currently stands, as the screen needs to render it. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    /** [progress] is null while the download size is unknown. */
    data class Downloading(val progress: Float?) : UpdateState
    data class Failed(val message: String) : UpdateState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val updater = AppUpdater(app)

    val themeMode: StateFlow<ThemeMode> =
        settings.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val suspensionEnabled: StateFlow<Boolean> =
        settings.suspensionEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val suspensionNotifyEnabled: StateFlow<Boolean> =
        settings.suspensionNotifyEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val suspensionNotifyDays: StateFlow<Int> =
        settings.suspensionNotifyDays
            .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsStore.DEFAULT_NOTIFY_DAYS)

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    val versionName: String = BuildConfig.VERSION_NAME
    val versionCode: Int = BuildConfig.VERSION_CODE
    val buildType: String = BuildConfig.BUILD_TYPE
    val updateRepo: String = AppUpdater.REPO

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setSuspensionEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setSuspensionEnabled(enabled) }

    fun setSuspensionNotifyEnabled(enabled: Boolean) =
        viewModelScope.launch { settings.setSuspensionNotifyEnabled(enabled) }

    fun setSuspensionNotifyDays(days: Int) =
        viewModelScope.launch { settings.setSuspensionNotifyDays(days) }

    fun checkForUpdate() {
        if (_update.value is UpdateState.Checking || _update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            _update.value = when (val result = updater.check()) {
                is UpdateCheck.UpToDate -> UpdateState.UpToDate
                is UpdateCheck.Available -> UpdateState.Available(result.update)
                is UpdateCheck.Failed -> UpdateState.Failed(result.message)
            }
        }
    }

    fun install(update: AvailableUpdate) {
        if (_update.value is UpdateState.Downloading) return
        _update.value = UpdateState.Downloading(null)
        viewModelScope.launch {
            updater.downloadAndInstall(update) { progress ->
                _update.update { current ->
                    // Only while still downloading: a failure or a completed
                    // handover must not be overwritten by a late callback.
                    if (current is UpdateState.Downloading) UpdateState.Downloading(progress) else current
                }
            }.onSuccess {
                // The installer has taken over; leave the offer on screen so
                // that declining the system prompt does not look like success.
                _update.value = UpdateState.Available(update)
            }.onFailure {
                _update.value = UpdateState.Failed(it.message ?: "ダウンロードに失敗しました")
            }
        }
    }

    fun releasePageIntent() = updater.releasePageIntent()
}
