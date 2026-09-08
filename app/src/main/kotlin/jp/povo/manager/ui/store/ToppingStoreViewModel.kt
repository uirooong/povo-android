package jp.povo.manager.ui.store

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.core.model.ToppingOrder
import jp.povo.manager.core.model.ToppingProduct
import jp.povo.manager.core.model.ToppingSection
import jp.povo.manager.data.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where a purchase currently stands. */
sealed interface PurchaseState {
    data object Idle : PurchaseState

    /** The buyer has picked something and is being asked to confirm. */
    data class Confirming(val product: ToppingProduct) : PurchaseState

    /** The order is with the service. */
    data class Placing(val product: ToppingProduct) : PurchaseState

    /**
     * The card issuer wants the buyer to authenticate.
     *
     * Nothing has been charged at this point — abandoning here abandons the
     * order.
     */
    data class Authorising(val product: ToppingProduct, val order: ToppingOrder) : PurchaseState

    data class Done(val product: ToppingProduct) : PurchaseState
    data class Failed(val message: String) : PurchaseState
}

data class StoreState(
    val sections: List<ToppingSection> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class ToppingStoreViewModel(app: Application, private val accountId: String) :
    AndroidViewModel(app) {

    private val repo = AccountRepository.get(app)

    private val _state = MutableStateFlow(StoreState())
    val state: StateFlow<StoreState> = _state.asStateFlow()

    private val _purchase = MutableStateFlow<PurchaseState>(PurchaseState.Idle)
    val purchase: StateFlow<PurchaseState> = _purchase.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.toppingCatalogue(accountId) }.fold(
                onSuccess = { sections ->
                    _state.value = StoreState(sections = sections, loading = false)
                },
                onFailure = { e ->
                    _state.value = StoreState(
                        loading = false,
                        error = e.message ?: "トッピング一覧を取得できませんでした",
                    )
                },
            )
        }
    }

    fun select(product: ToppingProduct) {
        // Only from rest: a second tap while an order is in flight must not
        // start another one.
        if (_purchase.value is PurchaseState.Idle || _purchase.value is PurchaseState.Failed) {
            _purchase.value = PurchaseState.Confirming(product)
        }
    }

    fun dismissPurchase() {
        _purchase.value = PurchaseState.Idle
    }

    /**
     * Sends the order. Called only from the confirmation step — this is the
     * point where money starts moving.
     */
    fun confirm(product: ToppingProduct) {
        if (_purchase.value !is PurchaseState.Confirming) return
        _purchase.value = PurchaseState.Placing(product)
        viewModelScope.launch {
            repo.orderTopping(accountId, product.id).fold(
                onSuccess = { order ->
                    _purchase.value = if (order.needsChallenge) {
                        PurchaseState.Authorising(product, order)
                    } else {
                        // No challenge means the service already took payment.
                        onPurchased(product)
                        PurchaseState.Done(product)
                    }
                },
                onFailure = { e ->
                    _purchase.value = PurchaseState.Failed(e.message ?: "購入できませんでした")
                },
            )
        }
    }

    /** The issuer approved and sent the buyer back. */
    fun authorised(product: ToppingProduct) {
        _purchase.value = PurchaseState.Done(product)
        onPurchased(product)
    }

    /**
     * The buyer left the challenge.
     *
     * Reported as an abandonment rather than a failure: no charge was made, and
     * calling it an error would suggest something went wrong.
     */
    fun abandoned() {
        _purchase.value = PurchaseState.Failed("購入を中止しました。請求は発生していません。")
    }

    private fun onPurchased(product: ToppingProduct) = viewModelScope.launch {
        // The line now holds something it did not before, and the whole app
        // reads from the cache — so refresh rather than wait for the periodic
        // worker to notice.
        repo.refreshAccount(accountId)
    }
}
