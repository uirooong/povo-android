package jp.povo.manager.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.core.model.DataBucket
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.data.BillsPolicy
import jp.povo.manager.core.model.SuspensionForecast
import jp.povo.manager.data.RefreshResult
import jp.povo.manager.data.SettingsStore
import jp.povo.manager.data.SuspensionStore
import jp.povo.manager.data.db.AccountEntity
import jp.povo.manager.data.db.UsageSnapshotEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** One row on the dashboard: an account plus its most recent reading. */
data class AccountCard(
    val account: AccountEntity,
    val usage: UsageSnapshotEntity?,
    val buckets: List<DataBucket>,
    /**
     * Set only when this line is inside the warning window the reader chose.
     *
     * Filtered in the view model rather than in the row so the list stays
     * quiet: most accounts have no anchor set and most that do are months
     * away, and a permanent countdown on every row would bury the one that
     * matters.
     */
    val suspension: SuspensionForecast? = null,
) {
    val needsLogin: Boolean get() = account.lastError?.contains("再ログイン") == true
    val blockedUntil: Long? get() = account.blockedUntil?.takeIf { it > System.currentTimeMillis() }
}

class AccountsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = AccountRepository.get(app)
    private val settings = SettingsStore(app)
    private val suspensions = SuspensionStore(app)

    val cards: StateFlow<List<AccountCard>> =
        combine(
            repo.observeAccounts(),
            repo.observeAllUsage(),
            suspensions.anchors,
            settings.suspensionEnabled,
            settings.suspensionNotifyDays,
        ) { accounts, snapshots, anchors, suspensionEnabled, warnWithin ->
            val today = LocalDate.now()
            accounts.map { account ->
                val snapshot = snapshots.firstOrNull { it.accountId == account.id }
                AccountCard(
                    account = account,
                    usage = snapshot,
                    buckets = snapshot?.let(repo::decodeBuckets).orEmpty(),
                    // The same threshold the notification uses, so the list and
                    // the notification never disagree about what is urgent.
                    suspension = anchors[account.id]
                        ?.takeIf { suspensionEnabled }
                        ?.forecast(today)
                        ?.takeIf { it.daysLeft <= warnWithin },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Sessions and the display cache are separate stores; make sure every
        // account that can still authenticate has a row to render.
        viewModelScope.launch { repo.syncFromSessions() }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun dismissMessage() = _message.update { null }

    /** Pull-to-refresh: every account at once. */
    fun refreshAll() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            // ALWAYS: the user asked for this one, so nothing is throttled.
            val outcomes = repo.refreshAll(BillsPolicy.ALWAYS)
            val failed = outcomes.count { it.result != RefreshResult.OK }
            _message.value = when {
                outcomes.isEmpty() -> null
                failed == 0 -> "${outcomes.size} 件すべて更新しました"
                else -> "$failed 件の更新に失敗しました"
            }
            _refreshing.value = false
        }
    }

    fun remove(accountId: String) = viewModelScope.launch { repo.removeAccount(accountId) }
}
