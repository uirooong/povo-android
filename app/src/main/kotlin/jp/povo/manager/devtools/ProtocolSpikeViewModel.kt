package jp.povo.manager.devtools

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.povo.manager.core.DeviceId
import jp.povo.manager.core.Jwt
import jp.povo.manager.core.PovoAccountClient
import jp.povo.manager.core.PovoSession
import jp.povo.manager.core.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import uniffi.povo_core.ActionType
import uniffi.povo_core.AuthChallenge
import uniffi.povo_core.PovoException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1 protocol verification spike.
 *
 * A development tool, not a product screen. It answers, against the real
 * service, questions that cannot be settled by reading code — which login route
 * actually works, whether a captcha or PIN is ever demanded, and what the
 * account payloads really look like field for field.
 *
 * Responses are surfaced verbatim and can be written to a file with
 * [exportLog], so they can be pulled off the device and turned into parser
 * fixtures. Nothing here interprets a payload.
 */
class ProtocolSpikeViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val email: String = "",
        val phone: String = "",
        val otp: String = "",
        val deviceId: String = DeviceId.generate(),
        val busy: Boolean = false,
        val loggedIn: Boolean = false,
        val restored: Boolean = false,
        val requiredActions: List<String> = emptyList(),
        val log: List<Entry> = emptyList(),
        /** Every account with a stored session, so several can be held at once. */
        val accounts: List<PovoSession> = emptyList(),
        val activeAccountId: String? = null,
        val customPath: String = "",
        val customPrefix: String = "",
        val customVersion: String = "",
        val customBody: String = "",
    )

    data class Entry(val at: String, val label: String, val body: String, val ok: Boolean)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val sessions = SessionStore(app)

    private var client: PovoAccountClient? = null
    private var authId: String? = null
    private var lastAction: ActionType? = null

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        restoreSession()
    }

    fun onEmailChange(v: String) = _state.update { it.copy(email = v.trim()) }
    fun onPhoneChange(v: String) = _state.update { it.copy(phone = v.trim()) }
    fun onOtpChange(v: String) = _state.update { it.copy(otp = v.trim()) }

    /**
     * Starts a blank account slot: a fresh device id and no token, leaving the
     * already-stored accounts alone. Each account needs its own device id — the
     * server binds sessions to it, so reusing one across accounts risks the
     * earlier session being invalidated.
     */
    fun addAccount() {
        closeClient()
        _state.update {
            it.copy(
                deviceId = DeviceId.generate(),
                email = "",
                phone = "",
                otp = "",
                loggedIn = false,
                restored = false,
                activeAccountId = null,
                requiredActions = emptyList(),
            )
        }
        log("アカウント追加", "新しい device_id を生成しました。メールアドレスを入れてログインしてください。", ok = true)
    }

    fun switchTo(accountId: String) {
        val saved = sessions.load().firstOrNull { it.accountId == accountId } ?: return
        activate(saved)
        log("アカウント切替", "${saved.label ?: saved.accountId} に切り替えました。", ok = true)
        if (saved.isExpired) refreshToken()
    }

    fun removeAccount(accountId: String) {
        sessions.remove(accountId)
        if (_state.value.activeAccountId == accountId) closeClient()
        refreshAccountList()
        log("アカウント削除", "$accountId を削除しました。", ok = true)
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    fun logAsText(): String = _state.value.log.asReversed().joinToString("\n\n") { e ->
        "### [${e.at}] ${e.label} ${if (e.ok) "OK" else "FAILED"}\n${e.body}"
    }

    // ---- session persistence ----------------------------------------------

    /**
     * Restores the previous session so that reinstalling the app does not cost
     * another OTP round trip. The token is short-lived (about 20 minutes in
     * practice), so an expired one is refreshed rather than discarded.
     */
    private fun restoreSession() {
        val saved = sessions.load()
        refreshAccountList()
        val first = saved.firstOrNull() ?: return
        activate(first)

        val expiry = Jwt.expiresAtEpochSeconds(first.authToken)
        log(
            "セッション復元",
            "保存済みアカウント ${saved.size} 件。\n" +
                "アクティブ = ${first.label ?: first.accountId}\n" +
                "exp = $expiry (${expiry?.let { Date(it * 1000) }})\n" +
                if (first.isExpired) "期限切れ → 自動更新します。" else "有効です。",
            ok = true,
        )
        if (first.isExpired) refreshToken()
    }

    private fun activate(saved: PovoSession) {
        closeClient()
        client = PovoAccountClient.create(
            accountId = saved.accountId,
            deviceId = saved.deviceId,
            authToken = saved.authToken,
            sin = saved.sin,
        )
        _state.update {
            it.copy(
                deviceId = saved.deviceId,
                email = saved.email ?: "",
                phone = saved.phoneNo ?: "",
                loggedIn = true,
                restored = true,
                activeAccountId = saved.accountId,
            )
        }
    }

    private fun refreshAccountList() = _state.update { it.copy(accounts = sessions.load()) }

    /** Accounts are keyed by the identifier used to log in, so they stay distinct. */
    private fun accountIdFor(state: State): String =
        state.activeAccountId
            ?: state.email.lowercase().ifBlank { state.phone }.ifBlank { "account-${state.deviceId.take(8)}" }

    private suspend fun persistSession(profileLabel: String? = null) {
        val c = client ?: return
        val token = c.authToken() ?: return
        val s = _state.value
        val id = accountIdFor(s)
        sessions.upsert(
            PovoSession(
                accountId = id,
                deviceId = s.deviceId,
                authToken = token,
                sin = c.serviceInstanceNumber(),
                email = s.email.ifBlank { null },
                phoneNo = s.phone.ifBlank { null },
                externalId = Jwt.externalId(token),
                label = profileLabel
                    ?: sessions.load().firstOrNull { it.accountId == id }?.label,
            ),
        )
        _state.update { it.copy(activeAccountId = id, accounts = sessions.load()) }
    }

    // ---- login flow -------------------------------------------------------

    fun step1RequestLoginAction() = run("1. users/login/action") {
        val s = _state.value
        val response = ensureClient().requestLoginAction(
            email = s.email.ifBlank { null },
            phoneNo = s.phone.ifBlank { null },
        )
        _state.update { it.copy(requiredActions = response.actions.map { a -> a.actionType.name }) }
        buildString {
            appendLine("actions = ${response.actions.map { it.actionType.name }}")
            appendLine("message = ${response.message}")
            response.actions.forEach { appendLine("  ${it.actionType} :: ${it.metadata?.message}") }
        }
    }

    fun step2SendEmailOtp() = run("2. otp (LOGIN_EMAIL_OTP)") {
        val s = _state.value
        require(s.email.isNotBlank()) { "メールアドレスを入力してください" }
        val response = ensureClient().sendEmailOtp(s.email)
        authId = response.authId
        lastAction = ActionType.EMAIL_OTP
        "auth_id = ${response.authId}\ndevice_id = ${response.deviceId}"
    }

    fun step2SendMobileOtp() = run("2. otp (LOGIN_MOBILE_OTP)") {
        val s = _state.value
        require(s.phone.isNotBlank()) { "電話番号を入力してください" }
        val response = ensureClient().sendMobileOtp(s.phone)
        authId = response.authId
        lastAction = ActionType.LOGIN_OTP
        "auth_id = ${response.authId}\ndevice_id = ${response.deviceId}"
    }

    fun step3Login() = run("3. users/auth") {
        val s = _state.value
        val id = authId ?: error("先に OTP を送信してください")
        require(s.otp.isNotBlank()) { "OTP コードを入力してください" }

        val challenge = AuthChallenge(authId = id, otpCode = s.otp, deviceId = s.deviceId)
        val emailFlow = lastAction == ActionType.EMAIL_OTP
        val token = ensureClient().login(
            emailAuth = if (emailFlow) challenge else null,
            mobileAuth = if (emailFlow) null else challenge,
        )

        _state.update { it.copy(loggedIn = token.pinToken == null) }
        if (token.pinToken == null) persistSession()

        val exp = Jwt.expiresAtEpochSeconds(token.authToken)
        buildString {
            appendLine("first_login = ${token.firstLogin}")
            appendLine("pin_token   = ${token.pinToken ?: "(none — no PIN challenge, as expected)"}")
            appendLine("external_id = ${Jwt.externalId(token.authToken)}")
            appendLine("exp         = $exp (${exp?.let { Date(it * 1000) }})")
            appendLine("有効期間     = ${exp?.minus(System.currentTimeMillis() / 1000)} 秒")
            appendLine("token       = ${token.authToken.take(24)}… (${token.authToken.length} chars)")
        }
    }

    // ---- data endpoints ---------------------------------------------------

    fun fetchProfile() = run("GET users?include_telco=true") {
        val profile = ensureClient().getProfile()
        // getProfile() discovers the SIN; keep it, and label the account with the
        // phone number so the switcher shows something recognisable.
        persistSession(profileLabel = profile.telcoInfo?.phoneNo ?: profile.firstName)
        val telco = profile.telcoInfo
        buildString {
            appendLine("external_id = ${profile.externalId}")
            appendLine("user_type   = ${profile.userType}")
            appendLine("dob         = ${profile.dob?.year}-${profile.dob?.month}-${profile.dob?.day}")
            appendLine("-- telco_info --")
            appendLine("service_instance_no = ${telco?.serviceInstanceNo}")
            appendLine("status              = ${telco?.status}")
            appendLine("plan_name           = ${telco?.billingInfo?.planName}")
            appendLine()
            appendLine("SIN now cached on client = ${ensureClient().serviceInstanceNumber()}")
        }
    }

    fun fetchPlanUsage() = run("GET account/usage/plan/get") { ensureClient().getPlanUsageJson() }

    fun fetchPlanDetails() = run("GET account/plan/details/get [x-verified:false]") {
        ensureClient().getPlanDetailsJson()
    }

    fun fetchUsageDataDetails() = run("GET account/usage/data/details/get [x-verified:false]") {
        ensureClient().getUsageDataDetailsJson()
    }

    /** Runs both bills paths so the typed and raw views can be compared. */
    fun fetchBills() = run("GET layout/bills/info (typed vs raw)") {
        val c = ensureClient()
        val typed = c.getBillsInfoTyped()
        val raw = c.getBillsInfoJson()
        buildString {
            appendLine("povo-core getBillsInfo() -> ${typed.size} entries")
            typed.take(5).forEach { appendLine("  [${it.section}] ${it.billId} / ${it.label}") }
            appendLine()
            appendLine("--- raw JSON ---")
            appendLine(raw)
        }
    }

    fun fetchReferral() = run("GET account/referral/code/get") { ensureClient().getReferralCodeJson() }

    /**
     * The subscribed-toppings list.
     *
     * Quilt routes are not localized — `/api/v1/quilt/page/{page}` with no
     * `/jp/ja/mobile/` segment — which is why an earlier attempt through the
     * ordinary escape hatch 404'd.
     */
    fun fetchQuiltPlan() = run("GET /api/v1/quilt/page/user-plan-details-v2") {
        ensureClient().getQuiltPageJson("user-plan-details-v2")
    }

    fun fetchQuiltHome() = run("GET /api/v1/quilt/page/dashboard-v2") {
        ensureClient().getQuiltPageJson("dashboard-v2")
    }

    /** The official order-history screen, as a quilt page. */
    fun fetchQuiltOrderHistory() = run("GET /api/v1/quilt/page/order-history") {
        ensureClient().getQuiltPageJson("order-history")
    }

    /** Purchase history — a POST with a card-priority map, not a GET. */
    fun fetchTelcoDashboard() = run("POST telco/dashboard") {
        ensureClient().postTelcoDashboardJson()
    }

    /**
     * Downloads the newest invoice PDF and reports what it actually is.
     *
     * Two things are unresolved and only the bytes can settle them: whether the
     * file is encrypted at all, and — if it is — whether the password is the
     * birth date as `YYYY-MM-DD` (what the reference Windows client uses) or
     * `YYYYMMDD` (what povo-core's docstring claims). Rather than pull in a PDF
     * library just to find out, this checks the `%PDF` magic and looks for an
     * `/Encrypt` dictionary in the trailer, which is what makes a PDF
     * password-protected in the first place.
     */
    fun fetchBillPdf() = run("GET billing/bills/download/{billId}") {
        val c = ensureClient()
        val billId = Json.parseToJsonElement(c.getBillsInfoJson())
            .jsonObject["past_bills"]?.jsonObject?.get("list")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("billId")?.jsonPrimitive?.content
            ?: error("past_bills.list[] に billId がありません")

        val bytes = c.downloadBillPdf(billId)
        val head = bytes.take(8).joinToString(" ") { "%02x".format(it) }
        val magic = bytes.take(5).toByteArray().decodeToString()
        // Scan the tail: the trailer, where /Encrypt lives, is at the end.
        val tail = bytes.takeLast(4096).toByteArray().decodeToString(throwOnInvalidSequence = false)
        val encrypted = "/Encrypt" in tail || "/Encrypt" in bytes.take(4096).toByteArray()
            .decodeToString(throwOnInvalidSequence = false)

        val dob = c.getProfile().dob
        val birth = dob?.let {
            "%04d-%02d-%02d".format(it.year ?: 0, it.month ?: 0, it.day ?: 0)
        }

        // Keep the bytes so the password format can be settled off-device
        // without linking a PDF library into the app just to run one experiment.
        val pdfFile = withContext(Dispatchers.IO) {
            File(getApplication<Application>().filesDir, "captures")
                .apply { mkdirs() }
                .resolve("bill.pdf")
                .also { it.writeBytes(bytes) }
        }

        buildString {
            appendLine("billId = $billId")
            appendLine("bytes  = ${bytes.size}")
            appendLine("magic  = ${magic.replace("\n", "\\n")}  (hex $head)")
            appendLine("PDF?   = ${magic.startsWith("%PDF")}")
            appendLine("/Encrypt 検出 = $encrypted")
            appendLine("保存先 = ${pdfFile.absolutePath}")
            appendLine()
            if (encrypted) {
                appendLine("パスワード候補 (生年月日より):")
                appendLine("  YYYY-MM-DD = $birth")
                appendLine("  YYYYMMDD   = ${birth?.replace("-", "")}")
            } else {
                appendLine("暗号化されていない → パスワード不要。")
            }
        }
    }

    fun onCustomPathChange(v: String) = _state.update { it.copy(customPath = v.trim()) }
    fun onCustomPrefixChange(v: String) = _state.update { it.copy(customPrefix = v.trim()) }
    fun onCustomVersionChange(v: String) = _state.update { it.copy(customVersion = v.trim()) }

    /** Not trimmed: a JSON body is passed through as typed. */
    fun onCustomBodyChange(v: String) = _state.update { it.copy(customBody = v) }

    /**
     * Calls an arbitrary route through povo-core's generic `get_json`.
     *
     * Only a handful of the roughly 200 endpoints recovered from the app have
     * typed methods, and the ones we need are not always among them — the
     * subscribed-toppings list, for instance, is not in `account/usage/plan/get`
     * where the static analysis suggested it would be. This makes hunting for
     * the right route a matter of typing it in rather than rebuilding.
     *
     * URL shape is `{prefix}/{version}/jp/{locale}/mobile/{path}`, so a quilt
     * page is prefix `/api`, version `v1`, path `quilt/page/...`.
     *
     * A path starting with `/` bypasses that construction entirely and is sent
     * as-is. The formula cannot express every route: quilt pages are not
     * localized at all, and some endpoints are only served under locale `en`
     * (`layout/profile/info` answers 500 on `ja`), which the fixed `ja` above
     * cannot reach. Typing the whole path is more use than a locale field
     * because it covers shapes the formula does not have a slot for.
     */
    fun fetchCustom() {
        val s = _state.value
        if (s.customPath.isBlank()) {
            log("任意エンドポイント", "パスを入力してください", ok = false)
            return
        }
        if (s.customPath.startsWith("/")) {
            run("GET ${s.customPath} (raw)") { ensureClient().getRawJson(s.customPath) }
            return
        }
        val version = s.customVersion.ifBlank { "v4" }
        val prefix = s.customPrefix.ifBlank { null }
        run("GET ${prefix.orEmpty()}/$version/jp/ja/mobile/${s.customPath}") {
            ensureClient().getJson(s.customPath, prefix, version, "ja")
        }
    }

    /**
     * POSTs the same arbitrary route, with the body from the form.
     *
     * Separate from [fetchCustom] rather than a mode on it, so that reaching a
     * write endpoint is always a deliberate press. The routes worth probing
     * here (the web-front page session, for one) only answer to POST.
     */
    fun postCustom() {
        val s = _state.value
        if (s.customPath.isBlank()) {
            log("任意エンドポイント (POST)", "パスを入力してください", ok = false)
            return
        }
        val body = s.customBody.ifBlank { "{}" }
        val version = s.customVersion.ifBlank { "v4" }
        val prefix = s.customPrefix.ifBlank { null }
        run("POST ${prefix.orEmpty()}/$version/jp/ja/mobile/${s.customPath}") {
            ensureClient().postJson(s.customPath, body, prefix, version, "ja")
        }
    }

    fun refreshToken() = run("GET users/token") {
        val token = ensureClient().refreshToken()
        persistSession()
        val exp = Jwt.expiresAtEpochSeconds(token.authToken)
        "exp = $exp (${exp?.let { Date(it * 1000) }})\n" +
            "有効期間 = ${exp?.minus(System.currentTimeMillis() / 1000)} 秒"
    }

    /**
     * Runs the endpoints that actually work, so a full capture is a single tap.
     *
     * `account/plan/details/get` and `account/usage/data/details/get` are left
     * out: both were recovered from static analysis only and both fail against
     * the live service (HTTP 500 and 491).
     */
    fun fetchEverything() {
        fetchProfile(); fetchPlanUsage(); fetchBills(); fetchReferral()
        fetchQuiltPlan(); fetchTelcoDashboard()
    }

    /**
     * Refreshes every stored account at once.
     *
     * This is the app's central premise under test: one `PovoClient` per
     * account, each with its own token and SIN, all fetched concurrently. If
     * two accounts ever bled into each other, this is where it would show — the
     * per-account SIN in the result would not match the account it came from.
     */
    fun fetchAllAccountsParallel() {
        val saved = sessions.load()
        if (saved.isEmpty()) {
            log("並列取得", "保存済みアカウントがありません。", ok = false)
            return
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val results = coroutineScope {
                saved.map { session ->
                    async(Dispatchers.IO) { refreshOne(session, startedAt) }
                }.awaitAll()
            }
            val elapsed = System.currentTimeMillis() - startedAt
            log(
                "並列取得 (${saved.size} アカウント)",
                results.joinToString("\n\n") + "\n\n合計 ${elapsed} ms",
                ok = results.none { it.contains("FAILED") },
            )
            _state.update { it.copy(busy = false, accounts = sessions.load()) }
        }
    }

    private suspend fun refreshOne(session: PovoSession, startedAt: Long): String {
        val began = System.currentTimeMillis() - startedAt
        return PovoAccountClient.create(
            accountId = session.accountId,
            deviceId = session.deviceId,
            authToken = session.authToken,
            sin = session.sin,
        ).use { c ->
            runCatching {
                if (session.isExpired) c.refreshToken()
                val profile = c.getProfile()
                val usage = c.getPlanUsageJson()
                val ended = System.currentTimeMillis() - startedAt
                buildString {
                    appendLine("[${session.label ?: session.accountId}] OK  (+${began}ms → +${ended}ms)")
                    appendLine("  sin      = ${c.serviceInstanceNumber()}")
                    appendLine("  phone    = ${profile.telcoInfo?.phoneNo}")
                    appendLine("  plan     = ${profile.telcoInfo?.billingInfo?.planName}")
                    appendLine("  usage len= ${usage.length} chars")
                }.trimEnd()
            }.getOrElse { t ->
                "[${session.label ?: session.accountId}] FAILED (+${began}ms)\n" +
                    describe(t).prependIndent("  ")
            }
        }
    }

    // ---- capture ----------------------------------------------------------

    /**
     * Writes the whole log to a file so a complete payload can be taken off the
     * device: screenshots cut long JSON lines off, logcat truncates a line at
     * about 4 KB, and the clipboard is not reachable from outside the app.
     *
     * The copy under `filesDir` is the one to read — scoped storage blocks both
     * `adb pull` and `run-as` from the external dir on API 30+, so only the
     * internal path is reliably retrievable:
     *
     *     adb exec-out run-as jp.povo.manager cat files/captures/spike-log.txt
     *
     * The external copy is written too, when available, because it is the one a
     * human can reach with a file manager.
     */
    fun exportLog() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val text = logAsText()
                    val internal = File(app.filesDir, "captures").apply { mkdirs() }
                        .resolve("spike-log.txt")
                    internal.writeText(text)

                    app.getExternalFilesDir(null)?.let { ext ->
                        runCatching {
                            File(ext, "captures").apply { mkdirs() }
                                .resolve("spike-log.txt").writeText(text)
                        }
                    }
                    "${internal.absolutePath} (${text.length} chars)"
                }
            }
            result.fold(
                onSuccess = {
                    Log.i(TAG, "log exported to $it")
                    log("エクスポート", "保存しました:\n$it", ok = true)
                },
                onFailure = { log("エクスポート", describe(it), ok = false) },
            )
        }
    }

    // ---- plumbing ---------------------------------------------------------

    private fun ensureClient(): PovoAccountClient =
        client ?: PovoAccountClient.create(
            accountId = accountIdFor(_state.value),
            deviceId = _state.value.deviceId,
        ).also { client = it }

    private fun run(label: String, block: suspend () -> String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.fold(
                onSuccess = { log(label, it, ok = true) },
                onFailure = { log(label, describe(it), ok = false) },
            )
            _state.update { it.copy(busy = false) }
        }
    }

    private fun describe(t: Throwable): String = when (t) {
        is PovoException.Api -> buildString {
            appendLine("HTTP ${t.status}")
            appendLine("message = ${t.serverMessage}")
            appendLine("code    = ${t.code}")
            appendLine("title   = ${t.title}")
            appendLine("detail  = ${t.detail}")
            t.blockUntilEpochMillis?.let {
                appendLine("block_until = $it (${Date(it)}) — back off until then")
            }
        }
        is PovoException.Unauthenticated -> "未認証: 先にログインしてください"
        is PovoException.Network -> "ネットワークエラー: ${t.message}"
        is PovoException.Decode -> "レスポンス解析エラー: ${t.message}"
        else -> "${t::class.simpleName}: ${t.message}"
    }

    private fun log(label: String, body: String, ok: Boolean) {
        Log.i(TAG, "[$label] ${if (ok) "OK" else "FAILED"}\n$body")
        _state.update {
            it.copy(log = it.log + Entry(clock.format(Date()), label, body.trim(), ok))
        }
    }

    private fun closeClient() {
        client?.close()
        client = null
        authId = null
        lastAction = null
    }

    override fun onCleared() {
        closeClient()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PovoSpike"
    }
}
