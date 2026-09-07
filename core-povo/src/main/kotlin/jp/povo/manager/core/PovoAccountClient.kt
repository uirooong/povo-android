package jp.povo.manager.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.povo_core.AuthChallenge
import uniffi.povo_core.BillEntry
import uniffi.povo_core.LoginActionsResponse
import uniffi.povo_core.OtpSendResponse
import uniffi.povo_core.PovoClient
import uniffi.povo_core.TokenResponse
import uniffi.povo_core.UserProfile

/**
 * One account's session.
 *
 * `PovoClient` is a uniffi object that holds its own `device_id`, `auth_token`,
 * `sin` and `user_type` behind an internal mutex, so the natural unit of
 * multi-account support is **one client instance per account**. Two accounts
 * refreshing at the same time touch entirely separate Rust objects and cannot
 * interfere with each other.
 *
 * Every povo-core method is a *blocking* call — the Rust side uses `ureq`, a
 * synchronous HTTP client — so all of them are wrapped here in
 * `withContext(io)`. That is the whole reason this class exists: it makes it
 * impossible to reach the blocking API from the main thread by accident.
 *
 * The per-instance [mutex] serialises calls for a single account. The Rust
 * mutex already prevents data races, but it does not stop two coroutines
 * interleaving a multi-step sequence (say, a token refresh landing halfway
 * through a login) and leaving the client in a surprising state.
 */
class PovoAccountClient private constructor(
    val accountId: String,
    val deviceId: String,
    private val client: PovoClient,
    private val io: CoroutineDispatcher,
) : AutoCloseable {

    private val mutex = Mutex()

    private suspend fun <T> call(block: (PovoClient) -> T): T =
        withContext(io) { mutex.withLock { block(client) } }

    // ---- session state ----------------------------------------------------

    /** The current auth token, or null when not authenticated. */
    suspend fun authToken(): String? = call { it.authToken() }

    suspend fun setAuthToken(token: String) = call { it.setAuthToken(token) }

    suspend fun clearAuthToken() = call { it.clearAuthToken() }

    /** The phone line ("SIN") that subsequent GETs are scoped to. */
    suspend fun serviceInstanceNumber(): String? = call { it.serviceInstanceNumber() }

    suspend fun setServiceInstanceNumber(sin: String?) = call { it.setServiceInstanceNumber(sin) }

    // ---- login ------------------------------------------------------------

    /**
     * Step 1. Asks the server which challenges this identifier requires; the
     * returned actions are ordered and must be satisfied in sequence.
     *
     * `captchaToken` is null: the reference client that logs in successfully
     * sends no captcha at all (its WebView-based captcha support was removed),
     * so we do not attempt to acquire one. If production starts demanding it,
     * this is where it would be threaded through.
     */
    suspend fun requestLoginAction(
        email: String? = null,
        phoneNo: String? = null,
        isdCode: String? = if (phoneNo != null) PovoProtocol.ISD_CODE_JP else null,
    ): LoginActionsResponse = call {
        it.requestLoginAction(email, isdCode, phoneNo, null)
    }

    /** Step 2a. Sends an OTP to the account's email address. */
    suspend fun sendEmailOtp(email: String): OtpSendResponse = call { it.sendEmailOtp(email) }

    /** Step 2b. Sends an OTP by SMS. */
    suspend fun sendMobileOtp(
        phoneNo: String,
        isdCode: String = PovoProtocol.ISD_CODE_JP,
    ): OtpSendResponse = call { it.sendMobileOtp(isdCode, phoneNo) }

    /**
     * Step 3. Completes login once every challenge has a matching
     * [AuthChallenge]. On success povo-core stores the token on the client
     * itself, so no explicit [setAuthToken] is needed.
     */
    suspend fun login(
        emailAuth: AuthChallenge? = null,
        mobileAuth: AuthChallenge? = null,
    ): TokenResponse = call { it.login(emailAuth, mobileAuth) }

    /**
     * Step-up PIN check. No PIN challenge has ever been observed in practice —
     * neither the reference client nor the API surface recovered from the app
     * has any notion of one — so this exists only so that an unexpected
     * `pinToken` is handled rather than silently dropped.
     */
    suspend fun validatePin(pinToken: String, pin: String): TokenResponse =
        call { it.validatePin(pinToken, pin, null) }

    /**
     * Refreshes the auth token.
     *
     * Call this only once [Jwt.isExpired] says the current token is done: the
     * server returns the *same* JWT when asked early, so refreshing eagerly
     * just burns a request.
     */
    suspend fun refreshToken(): TokenResponse = call { it.refreshToken() }

    // ---- account data -----------------------------------------------------

    /**
     * The logged-in user's profile. Also caches the SIN inside the Rust client,
     * which subsequent GETs need as a query parameter — so this must be the
     * first authenticated call after login.
     */
    suspend fun getProfile(): UserProfile = call { it.getProfile() }

    /**
     * The same payload [getProfile] parses, unparsed.
     *
     * povo-core's [UserProfile] does not model every field, and one the app
     * wants — `telco_info.activation_date`, the line's 開通日 — is among the
     * missing. Rather than a core change, this reads the raw document; it is
     * the identical endpoint, so nothing new is being asked of the service.
     * Note it does *not* update the client's cached SIN or user type the way
     * [getProfile] does, so it is a supplement to that call, not a substitute.
     */
    suspend fun getProfileJson(): String = call {
        it.getJson(PROFILE_PATH, USER_SERVICE_PREFIX, "v4", "ja")
    }

    suspend fun getPlanUsageJson(): String = call { it.getPlanUsageJson() }

    suspend fun getPlanDetailsJson(): String = call { it.getPlanDetailsJson() }

    suspend fun getUsageDataDetailsJson(): String = call { it.getUsageDataDetailsJson() }

    suspend fun getReferralCodeJson(): String = call { it.getReferralCodeJson() }

    /**
     * povo-core's typed billing timeline.
     *
     * Returns every section's entries tagged with `section`, but only the id,
     * label and the entry's raw JSON. [getBillsInfoJson] plus
     * [jp.povo.manager.core.json.BillsParser] is what the UI uses, because it
     * also recovers the amount, status and timestamp.
     */
    suspend fun getBillsInfoTyped(): List<BillEntry> = call { it.getBillsInfo() }

    /** Raw `layout/bills/info` document, parsed on the Kotlin side. */
    suspend fun getBillsInfoJson(): String = call {
        it.getJson("layout/bills/info", null, "v5", "ja")
    }

    /** Raw, still password-protected PDF bytes for one invoice. */
    suspend fun downloadBillPdf(billId: String): ByteArray = call { it.downloadBillPdf(billId) }

    /**
     * Quilt page — the layout document that carries the *subscribed* toppings.
     *
     * Quilt routes are not localized: they sit at `/api/v1/quilt/page/{page}`
     * with no `/jp/{locale}/mobile/` segment, so the ordinary [getJson] escape
     * hatch cannot address them and the server answers 404.
     */
    suspend fun getQuiltPageJson(page: String): String = call {
        it.getRawJson("/api/v1/quilt/page/$page")
    }

    /**
     * `telco/dashboard` — carries the boost purchase history under
     * `boost.detail.history[]`.
     *
     * This is a **POST**, not a GET: the body is a card-priority map telling the
     * server which dashboard sections to compose and at what depth. `detail`
     * has to be requested explicitly — asking only for `summary` returns the
     * purchasable catalog and no history at all.
     *
     * The full card map is sent rather than a boost-only one because the
     * minimal body is known to come back as `error_code_common` with an empty
     * detail section.
     */
    suspend fun postTelcoDashboardJson(): String = call { client ->
        // The card map is composed server-side and some accounts reject the full
        // one with error_code_common. Falling back to the two sections that
        // actually matter still yields the history.
        runCatching {
            client.postJson("telco/dashboard", null, "v4", "ja", DASHBOARD_REQUEST)
        }.getOrElse {
            client.postJson("telco/dashboard", null, "v4", "ja", DASHBOARD_REQUEST_BOOST_ONLY)
        }
    }

    /** Escape hatch for routes that do not follow the localized path shape. */
    suspend fun getRawJson(absolutePath: String): String = call { it.getRawJson(absolutePath) }

    /** Escape hatch for endpoints povo-core has no typed method for. */
    suspend fun getJson(
        relativePath: String,
        prefix: String? = null,
        version: String = "v4",
        locale: String = "ja",
    ): String = call { it.getJson(relativePath, prefix, version, locale) }

    /**
     * The POST counterpart of [getJson].
     *
     * povo-core has had `post_json` all along; it simply was not surfaced here
     * because nothing needed it. The web-front session route
     * (`webfront/users/session`) does, and it follows the same localized path
     * shape, so no core change is required to reach it.
     */
    suspend fun postJson(
        relativePath: String,
        bodyJson: String,
        prefix: String? = null,
        version: String = "v4",
        locale: String = "ja",
    ): String = call { it.postJson(relativePath, prefix, version, locale, bodyJson) }

    override fun close() = client.close()

    companion object {
        /**
         * Route of the profile document, matching povo-core's own
         * `get_profile()` exactly — see [getProfileJson].
         */
        private const val USER_SERVICE_PREFIX = "/api/v3/user-service"
        private const val PROFILE_PATH = "users?include_telco=true"

        /**
         * The dashboard card-priority map the official app sends
         * (`assets/dashboardmap/dashboard_request_v2`).
         *
         * Every section is requested so the response matches what the real
         * client would get; `boost.detail` is the one that actually carries
         * the purchase history.
         */
        const val DASHBOARD_REQUEST: String =
            """{"referral_share":{"md5":"","summary":{"priority":10}},"customize_plan":{"md5":"","summary":{"priority":10}},"bill":{"md5":"","summary":{"priority":10}},"error":{"md5":"","summary":{"priority":10}},"plus_addon":{"md5":"","summary":{"priority":10}},"image_banners":{"md5":"","summary":{"priority":10}},"birthday_bonus":{"md5":"","summary":{"priority":10}},"rating":{"md5":"","summary":{"priority":10}},"reference_card":{"md5":"","summary":{"priority":10}},"base":{"summary":{"priority":10},"detail":{"priority":5}},"boost":{"md5":"","summary":{"priority":10,"md5":""},"detail":{"priority":5,"md5":""}},"outstanding_bill":{"md5":"","summary":{"priority":10}},"credit_cap_bill":{"md5":"","summary":{"priority":10}},"roaming_card":{"md5":"","summary":{"priority":10}},"others":{"summary":{"priority":10},"detail":{"priority":5}},"whatsapp_passport":{"summary":{"priority":10}},"golden_circle":{"summary":{"priority":10}}}"""

        /** Fallback body: only the sections the history actually needs. */
        const val DASHBOARD_REQUEST_BOOST_ONLY: String =
            """{"base":{"summary":{"priority":10},"detail":{"priority":5}},"boost":{"md5":"","summary":{"priority":10,"md5":""},"detail":{"priority":5,"md5":""}}}"""

        /**
         * Builds a client for one account and restores its persisted session.
         *
         * @param deviceId the account's own stable device id (see [DeviceId]).
         * @param authToken a previously issued token, when resuming a session.
         * @param sin the account's phone line, so that the first authenticated
         *   GET is already scoped correctly instead of waiting for a profile
         *   fetch to discover it.
         */
        fun create(
            accountId: String,
            deviceId: String,
            authToken: String? = null,
            sin: String? = null,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): PovoAccountClient {
            val client = PovoClient(deviceId, PovoProtocol.APP_VERSION)
            client.setUserAgent(PovoProtocol.USER_AGENT)
            // povo-core builds the localized path itself and so omits the
            // headers the app's interceptor would set. Most endpoints do not
            // care, but some appear to branch on them, so send what the
            // reference client sends.
            PovoProtocol.EXTRA_HEADERS.forEach { (name, value) ->
                client.setExtraHeader(name, value)
            }
            authToken?.let(client::setAuthToken)
            sin?.let { client.setServiceInstanceNumber(it) }
            return PovoAccountClient(accountId, deviceId, client, io)
        }
    }
}
