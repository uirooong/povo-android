package jp.povo.manager.ui.detail

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.core.model.DataBucket
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.core.model.Purchase
import jp.povo.manager.core.model.Topping
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.data.RefreshResult
import jp.povo.manager.data.db.AccountEntity
import jp.povo.manager.data.db.BillEntity
import jp.povo.manager.data.db.UsageSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DetailState(
    val account: AccountEntity? = null,
    val usage: UsageSnapshotEntity? = null,
    val buckets: List<DataBucket> = emptyList(),
    val bills: List<BillEntity> = emptyList(),
    val toppings: List<Topping> = emptyList(),
    val purchases: List<Purchase> = emptyList(),
    /**
     * The povo pages this account has a way into, in
     * [PovoWebPageKind] declaration order — which is the order povo's own
     * profile screen lists them in.
     */
    val webPages: List<PovoWebPageKind> = emptyList(),
)

/** Everything needed to open an invoice the app cannot itself decrypt. */
data class PdfReady(val intent: Intent, val password: String?)

class AccountDetailViewModel(app: Application, private val accountId: String) :
    AndroidViewModel(app) {

    private val repo = AccountRepository.get(app)

    val state: StateFlow<DetailState> = combine(
        repo.observeAccount(accountId),
        repo.observeUsage(accountId),
        repo.observeBills(accountId),
        repo.observeExtras(accountId),
        repo.observeWebPages(accountId),
    ) { account, usage, bills, extras, webPages ->
        DetailState(
            account = account,
            usage = usage,
            buckets = usage?.let(repo::decodeBuckets).orEmpty(),
            bills = bills,
            toppings = extras?.let(repo::decodeToppings).orEmpty(),
            purchases = extras?.let(repo::decodePurchases).orEmpty(),
            // Sorted here because the rows come back keyed, not ordered, so
            // the page's own order does not survive storage. A kind the app no
            // longer knows is dropped rather than shown as a dead row: the
            // table survives an app update that removed one.
            webPages = webPages
                .mapNotNull { row -> runCatching { PovoWebPageKind.valueOf(row.kind) }.getOrNull() }
                .sorted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pdf = MutableStateFlow<PdfReady?>(null)
    val pdf: StateFlow<PdfReady?> = _pdf.asStateFlow()

    fun dismissMessage() = _message.update { null }
    fun consumePdf() = _pdf.update { null }

    fun refresh() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val outcome = repo.refreshAccount(accountId)
            _message.value = when (outcome.result) {
                RefreshResult.OK -> null
                RefreshResult.BLOCKED -> "サーバーの要求により更新を控えています"
                RefreshResult.NEEDS_LOGIN -> "再ログインが必要です"
                else -> outcome.message ?: "更新に失敗しました"
            }
            _busy.value = false
        }
    }

    /**
     * Fetches an invoice and hands it to whatever app can display a PDF.
     *
     * The file is not opened in-app on purpose: these invoices are encrypted,
     * and `PdfRenderer` cannot open a password-protected PDF at all. So the
     * bytes are written to the shared cache, handed over through a
     * [FileProvider] URI, and the password is surfaced alongside so the reader
     * can type it into the viewer.
     */
    fun openBillPdf(billId: String) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching {
                val bytes = repo.downloadBillPdf(accountId, billId)
                withContext(Dispatchers.IO) {
                    val dir = File(getApplication<Application>().cacheDir, "bills").apply { mkdirs() }
                    val file = File(dir, "$billId.pdf").apply { writeBytes(bytes) }
                    val uri = FileProvider.getUriForFile(
                        getApplication(),
                        "${getApplication<Application>().packageName}.fileprovider",
                        file,
                    )
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
            }.fold(
                onSuccess = { intent ->
                    // Verified against a real invoice: the hyphenated birth date
                    // opens the file and the unhyphenated form does not.
                    _pdf.value = PdfReady(intent, state.value.account?.birthDate)
                },
                onFailure = { _message.value = it.message ?: "PDF を取得できませんでした" },
            )
            _busy.value = false
        }
    }

    fun remove(onRemoved: () -> Unit) = viewModelScope.launch {
        repo.removeAccount(accountId)
        onRemoved()
    }
}
