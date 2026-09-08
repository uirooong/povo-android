package jp.povo.manager.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.core.DeviceId
import jp.povo.manager.core.Jwt
import jp.povo.manager.core.PovoAccountClient
import jp.povo.manager.core.PovoSession
import jp.povo.manager.data.AccountRepository
import jp.povo.manager.data.describe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uniffi.povo_core.ActionType
import uniffi.povo_core.AuthChallenge
import uniffi.povo_core.PovoException

/**
 * Drives the add-account flow.
 *
 * The steps are not fixed: `users/login/action` returns the ordered list of
 * challenges the server wants for this identifier, and the wizard walks it.
 * In practice only `EMAIL_OTP` has been observed, but an account configured for
 * SMS would return `LOGIN_OTP` instead and the same loop handles it.
 *
 * No captcha token is sent. The known-working reference client removed its
 * captcha support entirely and logs in fine without one, which live testing
 * confirmed.
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    enum class Step { IDENTIFIER, OTP, DONE }

    data class State(
        val step: Step = Step.IDENTIFIER,
        val email: String = "",
        val phone: String = "",
        val otp: String = "",
        val useSms: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        /** Challenges the server asked for, in order. */
        val pending: List<ActionType> = emptyList(),
        val completed: List<ActionType> = emptyList(),
        val addedAccountId: String? = null,
    ) {
        val currentChallenge: ActionType? get() = pending.firstOrNull()
        val canSubmitIdentifier: Boolean
            get() = !busy && (email.isNotBlank() || phone.isNotBlank())
    }

    private val repo = AccountRepository.get(app)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var client: PovoAccountClient? = null
    private var deviceId: String = DeviceId.generate()
    private var authId: String? = null
    private val challenges = mutableListOf<Pair<ActionType, AuthChallenge>>()

    fun onEmailChange(v: String) = _state.update { it.copy(email = v.trim(), error = null) }
    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v.trim(), error = null) }
    fun onOtpChange(v: String) = _state.update { it.copy(otp = v.trim(), error = null) }
    fun onUseSmsChange(v: Boolean) = _state.update { it.copy(useSms = v) }
    fun dismissError() = _state.update { it.copy(error = null) }

    /** Step 1: ask which challenges this identifier needs, then start the first. */
    fun submitIdentifier() = launch {
        val s = _state.value
        val c = ensureClient()
        val response = c.requestLoginAction(
            email = s.email.ifBlank { null },
            phoneNo = s.phone.ifBlank { null },
        )
        val actions = response.actions.map { it.actionType }.ifEmpty {
            // The server has always named its challenges so far; fall back to
            // whichever identifier was supplied rather than dead-ending.
            listOf(if (s.email.isNotBlank()) ActionType.EMAIL_OTP else ActionType.LOGIN_OTP)
        }
        _state.update { it.copy(pending = actions, completed = emptyList()) }
        sendOtpFor(actions.first())
        _state.update { it.copy(step = Step.OTP) }
    }

    /** Re-sends the code for the challenge currently in progress. */
    fun resendOtp() = launch {
        _state.value.currentChallenge?.let { sendOtpFor(it) }
    }

    private suspend fun sendOtpFor(action: ActionType) {
        val s = _state.value
        val c = ensureClient()
        val response = when (action) {
            ActionType.EMAIL_OTP -> {
                require(s.email.isNotBlank()) { "メールアドレスを入力してください" }
                c.sendEmailOtp(s.email)
            }
            ActionType.LOGIN_OTP, ActionType.REGISTER_OTP -> {
                require(s.phone.isNotBlank()) { "電話番号を入力してください" }
                c.sendMobileOtp(s.phone)
            }
        }
        authId = response.authId
    }

    /**
     * Step 2: answer the current challenge. If the server asked for more than
     * one, move to the next; otherwise complete the login.
     */
    fun submitOtp() = launch {
        val s = _state.value
        val action = s.currentChallenge ?: error("認証ステップがありません")
        val id = authId ?: error("先にコードを送信してください")
        require(s.otp.isNotBlank()) { "コードを入力してください" }

        challenges += action to AuthChallenge(
            authId = id,
            otpCode = s.otp,
            deviceId = deviceId,
        )

        val remaining = s.pending.drop(1)
        _state.update {
            it.copy(pending = remaining, completed = it.completed + action, otp = "")
        }

        if (remaining.isNotEmpty()) {
            sendOtpFor(remaining.first())
            return@launch
        }
        finishLogin()
    }

    private suspend fun finishLogin() {
        val c = ensureClient()
        val emailAuth = challenges.firstOrNull { it.first == ActionType.EMAIL_OTP }?.second
        val mobileAuth = challenges.firstOrNull { it.first != ActionType.EMAIL_OTP }?.second

        val token = c.login(emailAuth = emailAuth, mobileAuth = mobileAuth)
        if (token.pinToken != null) {
            // Never observed in practice, and not implemented. Say so plainly
            // rather than silently storing a token that is not yet authorised.
            error("このアカウントは PIN 認証を要求しました。現在未対応です。")
        }

        val s = _state.value
        val externalId = Jwt.externalId(token.authToken)
        // povo's own id for the line, not the address used to sign in. The
        // address can be changed — this app can change it — and keying on it
        // meant the same line came back as a second account the next time
        // someone logged in. Falls back to the identifier typed in only when
        // the token carries no external id.
        val accountId = externalId ?: s.email.lowercase().ifBlank { s.phone }
        val session = PovoSession(
            accountId = accountId,
            deviceId = deviceId,
            authToken = token.authToken,
            email = s.email.ifBlank { null },
            phoneNo = s.phone.ifBlank { null },
            externalId = externalId,
        )
        repo.addAccount(session).getOrThrow()
        _state.update { it.copy(step = Step.DONE, addedAccountId = accountId) }
    }

    private fun ensureClient(): PovoAccountClient =
        client ?: PovoAccountClient.create(
            accountId = "pending-login",
            deviceId = deviceId,
        ).also { client = it }

    private fun launch(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }.onFailure { t ->
                _state.update {
                    it.copy(
                        error = when (t) {
                            is PovoException -> t.describe()
                            else -> t.message ?: "不明なエラー"
                        },
                    )
                }
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** Starts over, including a new device id — one per account, never shared. */
    fun reset() {
        client?.close()
        client = null
        challenges.clear()
        authId = null
        deviceId = DeviceId.generate()
        _state.value = State()
    }

    override fun onCleared() {
        client?.close()
        super.onCleared()
    }
}
