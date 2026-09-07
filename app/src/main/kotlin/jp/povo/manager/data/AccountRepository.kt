package jp.povo.manager.data

import android.content.Context
import android.util.Log
import jp.povo.manager.core.Jwt
import jp.povo.manager.core.PovoAccountClient
import jp.povo.manager.core.PovoSession
import jp.povo.manager.core.SessionStore
import jp.povo.manager.core.json.BillsParser
import jp.povo.manager.core.json.UsageParser
import jp.povo.manager.core.model.BillsDocument
import jp.povo.manager.core.model.DataBucket
import jp.povo.manager.core.model.PaymentMethod
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.core.model.Purchase
import jp.povo.manager.core.model.Topping
import jp.povo.manager.core.json.QuiltParser
import jp.povo.manager.data.db.AccountEntity
import jp.povo.manager.data.db.AccountExtrasEntity
import jp.povo.manager.data.db.BillEntity
import jp.povo.manager.data.db.PovoDatabase
import jp.povo.manager.data.db.UsageSnapshotEntity
import jp.povo.manager.widget.UsageDonutWidget
import jp.povo.manager.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uniffi.povo_core.PovoException
import uniffi.povo_core.UserProfile
import kotlin.random.Random

/**
 * The single place the app talks to povo through.
 *
 * Holds one [PovoAccountClient] per account — the arrangement the whole
 * multi-account design rests on, since each client owns its own token, device
 * id and phone line inside the Rust layer and therefore cannot bleed into
 * another account's requests.
 *
 * Room is the app's source of truth for display. Every screen and the widget
 * read from it, so a refresh that fails leaves the last good reading on screen
 * rather than blanking it.
 */
class AccountRepository private constructor(
    private val context: Context,
    private val db: PovoDatabase,
    private val sessions: SessionStore,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val clients = mutableMapOf<String, PovoAccountClient>()
    private val clientLock = Any()

    /**
     * Caps how many accounts talk to the service at once.
     *
     * Refreshing every account in parallel is the point of the app, but "all at
     * once, unthrottled" is how you get rate-limited. Three is enough to keep
     * the wall-clock win while staying polite.
     */
    private val networkSlots = Semaphore(MAX_CONCURRENT_ACCOUNTS)

    // ---- reads ------------------------------------------------------------

    fun observeAccounts(): Flow<List<AccountEntity>> = db.accounts().observeAll()
    fun observeAccount(id: String): Flow<AccountEntity?> = db.accounts().observe(id)
    fun observeUsage(id: String): Flow<UsageSnapshotEntity?> = db.usage().observe(id)
    fun observeAllUsage(): Flow<List<UsageSnapshotEntity>> = db.usage().observeAll()
    fun observeBills(id: String): Flow<List<BillEntity>> = db.bills().observe(id)
    fun observeExtras(id: String): Flow<AccountExtrasEntity?> = db.extras().observe(id)

    suspend fun accounts(): List<AccountEntity> = db.accounts().all()

    /**
     * One-shot read of every account's latest reading, keyed by account id.
     *
     * The widget renders once per update rather than observing, so it needs a
     * snapshot rather than a Flow.
     */
    suspend fun usageSnapshot(): Map<String, UsageSnapshotEntity> =
        db.usage().all().associateBy { it.accountId }

    fun decodeBuckets(snapshot: UsageSnapshotEntity): List<DataBucket> =
        runCatching { json.decodeFromString<List<DataBucket>>(snapshot.bucketsJson) }
            .getOrDefault(emptyList())

    fun decodeToppings(extras: AccountExtrasEntity): List<Topping> =
        runCatching { json.decodeFromString<List<Topping>>(extras.toppingsJson) }
            .getOrDefault(emptyList())

    fun decodePurchases(extras: AccountExtrasEntity): List<Purchase> =
        runCatching { json.decodeFromString<List<Purchase>>(extras.purchasesJson) }
            .getOrDefault(emptyList())

    // ---- account lifecycle -------------------------------------------------

    /**
     * Registers a freshly authenticated account and pulls its profile so the
     * list has something to show immediately.
     */
    suspend fun addAccount(session: PovoSession): Result<AccountEntity> = runCatching {
        sessions.upsert(session)
        val client = clientFor(session)
        val profile = withContext(Dispatchers.IO) { client.getProfile() }
        // getProfile() discovers the phone line; persist it so the next launch
        // starts already scoped instead of re-discovering it.
        sessions.upsert(session.copy(sin = client.serviceInstanceNumber(), label = profile.label()))
        val entity = profile.toEntity(session, sortOrder = db.accounts().all().size)
        db.accounts().upsert(entity)
        entity
    }

    /**
     * Makes Room reflect the sessions that actually exist.
     *
     * The two stores can drift: credentials live in the Keystore-backed session
     * file while the display cache lives in Room, so clearing app data, a failed
     * migration, or a restore can leave a valid session with no row to render.
     * Seeding a placeholder here means such an account shows up immediately and
     * fills in on the next refresh, instead of silently vanishing from the list.
     */
    suspend fun syncFromSessions() {
        val known = db.accounts().all().map { it.id }.toSet()
        sessions.load().filterNot { it.accountId in known }.forEachIndexed { index, session ->
            db.accounts().upsert(
                AccountEntity(
                    id = session.accountId,
                    label = session.label ?: session.phoneNo ?: session.email,
                    email = session.email,
                    phoneNo = session.phoneNo,
                    sin = session.sin,
                    externalId = session.externalId,
                    planName = null,
                    status = null,
                    birthDate = null,
                    sortOrder = known.size + index,
                ),
            )
        }
    }

    suspend fun removeAccount(id: String) {
        synchronized(clientLock) { clients.remove(id)?.close() }
        sessions.remove(id)
        db.usage().delete(id)
        db.bills().deleteFor(id)
        db.extras().delete(id)
        db.accounts().delete(id)
    }

    fun session(id: String): PovoSession? = sessions.load().firstOrNull { it.accountId == id }

    /** Exposed so the bills screen can show the PDF password. */
    suspend fun birthDate(id: String): String? = db.accounts().all().firstOrNull { it.id == id }?.birthDate

    /**
     * A currently-valid token for [id], renewed first if it has expired.
     *
     * Only for the payment page, which povo serves as a web view and marks
     * `needs_xauth` — it identifies the user from the same token the API calls
     * carry, so it has to be handed over rather than the page being opened
     * anonymously. Renewed here because a stale token would present the page
     * as logged out, which looks like a bug rather than an expiry.
     */
    /**
     * Replaces [id]'s stored token with one povo handed back through the web
     * view.
     *
     * `shop.povo.jp` rotates the session by navigating to a `webfront://` URL
     * carrying a new `auth_token`; the official app treats that as the new
     * native session, so ignoring it would leave this app holding a token the
     * service has already replaced. The cached client is dropped so the next
     * call rebuilds with the new token.
     */
    suspend fun updateAuthToken(id: String, token: String) {
        val session = session(id) ?: return
        if (session.authToken == token) return
        sessions.upsert(session.copy(authToken = token))
        invalidateClient(id)
        Log.i(TAG, "adopted a rotated token for $id")
    }

    suspend fun freshAuthToken(id: String): String? {
        val session = session(id) ?: return null
        return withContext(Dispatchers.IO) {
            val client = clientFor(session)
            runCatching { ensureFreshToken(client, session) }
            client.authToken()
        }
    }

    // ---- refresh -----------------------------------------------------------

    /**
     * Refreshes every account concurrently and reports what happened.
     *
     * Failures are per-account: one expired login does not stop the others.
     */
    suspend fun refreshAll(bills: BillsPolicy = BillsPolicy.IF_STALE): List<RefreshOutcome> =
        coroutineScope {
            sessions.load().map { session ->
                async(Dispatchers.IO) {
                    networkSlots.withPermit {
                        // A small stagger so several accounts do not land on the
                        // service in the same millisecond.
                        delay(Random.nextLong(0, STAGGER_MAX_MILLIS))
                        refresh(session, bills)
                    }
                }
            }.awaitAll().also { notifyWidget(it) }
        }

    suspend fun refreshAccount(
        id: String,
        bills: BillsPolicy = BillsPolicy.ALWAYS,
    ): RefreshOutcome {
        val session = session(id) ?: return RefreshOutcome(id, RefreshResult.NOT_FOUND)
        return withContext(Dispatchers.IO) { refresh(session, bills) }
            .also { notifyWidget(listOf(it)) }
    }

    /**
     * Redraws the widget when a refresh actually changed something.
     *
     * The widget renders from Room, so it goes stale after *any* successful
     * write — not only the periodic worker's. Doing this here rather than in
     * each caller means a new refresh entry point cannot forget it.
     */
    private suspend fun notifyWidget(outcomes: List<RefreshOutcome>) {
        if (outcomes.any { it.result == RefreshResult.OK }) {
            UsageWidget.refresh(context)
            UsageDonutWidget.refresh(context)
        }
    }

    private suspend fun refresh(session: PovoSession, policy: BillsPolicy): RefreshOutcome {
        val stored = db.accounts().all().firstOrNull { it.id == session.accountId }
        val blockedUntil = stored?.blockedUntil
        if (blockedUntil != null && blockedUntil > System.currentTimeMillis()) {
            // The service told us to back off; honour it rather than retrying.
            return RefreshOutcome(session.accountId, RefreshResult.BLOCKED, blockedUntil = blockedUntil)
        }

        val includeBills = policy.wants(stored?.billsFetchedAt)
        // Logged because it is otherwise invisible: a throttled run looks
        // identical on screen, and the only symptom of the throttle being wrong
        // is request volume against the service.
        Log.i(TAG, "refresh ${session.accountId} policy=$policy bills=$includeBills")

        return try {
            val client = clientFor(session)
            ensureFreshToken(client, session)

            val profile = client.getProfile()
            val usage = UsageParser.parse(client.getPlanUsageJson())
            val bills = if (includeBills) BillsParser.parse(client.getBillsInfoJson()) else null
            // Quilt pages are the only source for these. Their failure must not
            // lose an otherwise good usage reading, so they are fetched
            // best-effort and a null just leaves the previous values in place.
            val toppings = runCatching {
                QuiltParser.parseToppings(client.getQuiltPageJson(QUILT_PLAN_PAGE))
            }.getOrNull()
            val purchases = if (!includeBills) null else runCatching {
                QuiltParser.parsePurchases(client.getQuiltPageJson(QUILT_ORDERS_PAGE))
            }.getOrNull()
            // Throttled with the billing endpoints: a registered card changes
            // about as often as an invoice does. Only the masked number and the
            // change link are taken from this page — it also carries the
            // contractor's name, postal address and PIN mask, which the app has
            // no use for and deliberately does not read.
            val payment = if (!includeBills) null else runCatching {
                QuiltParser.parsePaymentMethod(client.getQuiltPageJson(QUILT_PROFILE_PAGE))
            }.getOrNull()

            persist(
                session, client, profile, usage, bills, toppings, purchases,
                billsAttempted = includeBills,
                payment = payment,
            )
            RefreshOutcome(session.accountId, RefreshResult.OK)
        } catch (e: PovoException) {
            val blockUntil = (e as? PovoException.Api)?.blockUntilEpochMillis
            val needsLogin = e is PovoException.Unauthenticated ||
                (e as? PovoException.Api)?.status?.toInt() == 401
            db.accounts().markFailed(session.accountId, e.describe(), blockUntil)
            Log.w(TAG, "refresh failed for ${session.accountId}", e)
            RefreshOutcome(
                accountId = session.accountId,
                result = if (needsLogin) RefreshResult.NEEDS_LOGIN else RefreshResult.FAILED,
                message = e.describe(),
                blockedUntil = blockUntil,
            )
        } catch (e: Exception) {
            db.accounts().markFailed(session.accountId, e.message.orEmpty(), null)
            Log.w(TAG, "refresh failed for ${session.accountId}", e)
            RefreshOutcome(session.accountId, RefreshResult.FAILED, e.message)
        }
    }

    /**
     * Renews the token only once it has actually expired.
     *
     * Tokens last twenty minutes and the service returns the *same* one when
     * asked early, so refreshing eagerly would spend a request for nothing.
     */
    private suspend fun ensureFreshToken(client: PovoAccountClient, session: PovoSession) {
        val current = client.authToken() ?: return
        if (!Jwt.isExpired(current)) return
        val renewed = client.refreshToken()
        sessions.upsert(session.copy(authToken = renewed.authToken))
    }

    private suspend fun persist(
        session: PovoSession,
        client: PovoAccountClient,
        profile: UserProfile,
        usage: PlanUsage?,
        bills: BillsDocument?,
        toppings: List<Topping>? = null,
        purchases: List<Purchase>? = null,
        billsAttempted: Boolean = true,
        payment: PaymentMethod? = null,
    ) {
        val now = System.currentTimeMillis()
        val existing = db.accounts().all().firstOrNull { it.id == session.accountId }
        db.accounts().upsert(
            profile.toEntity(
                session = session.copy(sin = client.serviceInstanceNumber()),
                sortOrder = existing?.sortOrder ?: db.accounts().all().size,
            ).copy(
                // Carried over on a throttled run, so a refresh that skipped
                // the profile page does not blank the card on screen.
                paymentMasked = payment?.maskedNumber ?: existing?.paymentMasked,
                paymentUpdateUrl = payment?.updateUrl ?: existing?.paymentUpdateUrl,
                paymentExitUrl = payment?.exitUrl ?: existing?.paymentExitUrl,
                lastRefreshedAt = now,
                // Records the attempt, not the outcome: an account with no
                // invoices parses to null, and treating that as "never fetched"
                // would defeat the throttle entirely.
                billsFetchedAt = if (billsAttempted) now else existing?.billsFetchedAt,
                lastError = null,
                blockedUntil = null,
            ),
        )

        if (usage != null) {
            db.usage().upsert(
                UsageSnapshotEntity(
                    accountId = session.accountId,
                    fetchedAt = now,
                    totalLeftKb = usage.totalLeftKb,
                    totalUsedKb = usage.totalUsedKb,
                    bucketsJson = json.encodeToString(usage.buckets),
                    promotionLine1 = usage.promotionLine1,
                    promotionLine2 = usage.promotionLine2,
                    rawJson = "",
                ),
            )
        }

        if (bills != null) {
            db.bills().replaceFor(
                session.accountId,
                bills.all.mapIndexed { index, bill ->
                    BillEntity(
                        accountId = session.accountId,
                        // Estimates carry no invoice id, so fall back to a
                        // positional key to keep the primary key unique.
                        rowKey = bill.billId ?: "${bill.status}-$index",
                        billId = bill.billId,
                        title = bill.title,
                        subtitle = bill.subtitle,
                        status = bill.status.name,
                        amountValue = bill.amount?.value,
                        amountPrefix = bill.amount?.prefix,
                        timeEpochMillis = bill.timeEpochMillis,
                        hasPdf = bill.hasPdf,
                    )
                },
            )
        }

        if (toppings != null || purchases != null) {
            // This row is written whole, so whichever half was not fetched has
            // to be carried over rather than encoded from null — otherwise a
            // throttled run (or one where a quilt page just failed) would
            // silently replace the stored list with an empty one.
            val previous = db.extras().get(session.accountId)
            db.extras().upsert(
                AccountExtrasEntity(
                    accountId = session.accountId,
                    fetchedAt = now,
                    toppingsJson = toppings?.let { json.encodeToString(it) }
                        ?: previous?.toppingsJson ?: EMPTY_JSON_ARRAY,
                    purchasesJson = purchases?.let { json.encodeToString(it) }
                        ?: previous?.purchasesJson ?: EMPTY_JSON_ARRAY,
                ),
            )
        }
    }

    /** The raw, still password-protected invoice bytes. */
    suspend fun downloadBillPdf(accountId: String, billId: String): ByteArray {
        val session = session(accountId) ?: error("unknown account $accountId")
        return withContext(Dispatchers.IO) {
            val client = clientFor(session)
            ensureFreshToken(client, session)
            client.downloadBillPdf(billId)
        }
    }

    // ---- clients -----------------------------------------------------------

    private fun clientFor(session: PovoSession): PovoAccountClient = synchronized(clientLock) {
        clients.getOrPut(session.accountId) {
            PovoAccountClient.create(
                accountId = session.accountId,
                deviceId = session.deviceId,
                authToken = session.authToken,
                sin = session.sin,
            )
        }
    }

    /** Drops a cached client so the next call picks up a re-authenticated session. */
    fun invalidateClient(accountId: String) = synchronized(clientLock) {
        clients.remove(accountId)?.close()
        Unit
    }

    companion object {
        private const val TAG = "PovoRepo"
        private const val EMPTY_JSON_ARRAY = "[]"
        private const val MAX_CONCURRENT_ACCOUNTS = 3
        private const val STAGGER_MAX_MILLIS = 250L
        private const val QUILT_PLAN_PAGE = "user-plan-details-v2"
        private const val QUILT_ORDERS_PAGE = "order-history"

        /**
         * Carries the payment method. Not in the endpoint list recovered
         * from the app — the routes that list suggested for this
         * (`layout/profile/info`, `profile/creditcard/update`) answer 500
         * and a redirect to a gateway host that no longer resolves.
         */
        private const val QUILT_PROFILE_PAGE = "profile"

        @Volatile
        private var instance: AccountRepository? = null

        fun get(context: Context): AccountRepository = instance ?: synchronized(this) {
            instance ?: AccountRepository(
                context.applicationContext,
                PovoDatabase.get(context),
                SessionStore(context.applicationContext),
            ).also { instance = it }
        }
    }
}

enum class RefreshResult { OK, FAILED, NEEDS_LOGIN, BLOCKED, NOT_FOUND }

/**
 * Whether a refresh should also pull the billing timeline and purchase history.
 *
 * Those two are the expensive half of a refresh — two extra requests per
 * account — and they describe things that change monthly, not by the minute.
 * The periodic worker runs every 15 minutes to keep the data allowance current,
 * so it asks for [IF_STALE] and picks the billing endpoints up about once an
 * hour; anything the user triggered by hand asks for [ALWAYS], because someone
 * looking at the screen expects what they see to be current.
 */
enum class BillsPolicy {
    ALWAYS,
    IF_STALE,
    ;

    /** @param lastFetchedAt [AccountEntity.billsFetchedAt] for this account. */
    fun wants(lastFetchedAt: Long?): Boolean = when (this) {
        ALWAYS -> true
        // Never fetched, or old enough. The threshold sits below a full hour so
        // that the fourth 15-minute run clears it instead of just missing and
        // slipping to the fifth.
        IF_STALE -> lastFetchedAt == null ||
            System.currentTimeMillis() - lastFetchedAt >= BILLS_MAX_AGE_MILLIS
    }

    private companion object {
        const val BILLS_MAX_AGE_MILLIS = 55 * 60 * 1000L
    }
}

data class RefreshOutcome(
    val accountId: String,
    val result: RefreshResult,
    val message: String? = null,
    val blockedUntil: Long? = null,
)

private fun UserProfile.label(): String? =
    telcoInfo?.phoneNo ?: firstName ?: email

private fun UserProfile.toEntity(session: PovoSession, sortOrder: Int) = AccountEntity(
    id = session.accountId,
    label = label() ?: session.label,
    email = email ?: session.email,
    phoneNo = telcoInfo?.phoneNo ?: session.phoneNo,
    sin = telcoInfo?.serviceInstanceNo ?: session.sin,
    externalId = externalId ?: session.externalId,
    planName = telcoInfo?.billingInfo?.planName,
    status = telcoInfo?.status,
    customerName = telcoInfo?.customerName,
    // `activation_date` is this line's; `initial_activation_date` is the
    // account's first ever, which differs only if the line was re-issued. The
    // service sends UTC midnight, so the time is dropped rather than converted
    // — shifting it into JST would move the date forward a day.
    activationDate = (telcoInfo?.activationDate ?: telcoInfo?.initialActivationDate)
        ?.substringBefore('T')?.takeIf(String::isNotBlank),
    birthDate = dob?.let { d ->
        val y = d.year ?: return@let null
        val m = d.month ?: return@let null
        val day = d.day ?: return@let null
        // Hyphenated: verified against a real encrypted invoice. The
        // unhyphenated form does not open the file.
        "%04d-%02d-%02d".format(y, m, day)
    },
    sortOrder = sortOrder,
)

/** A message worth showing a person, rather than the exception's toString. */
fun PovoException.describe(): String = when (this) {
    is PovoException.Unauthenticated -> "再ログインが必要です"
    is PovoException.Network -> "ネットワークに接続できません"
    is PovoException.Decode -> "サーバー応答を解釈できませんでした"
    is PovoException.InvalidArgument -> "入力が正しくありません"
    is PovoException.Api -> when (status.toInt()) {
        401 -> "再ログインが必要です"
        429 -> "リクエストが多すぎます。しばらく待ってください"
        in 500..599 -> "povo 側でエラーが発生しています (HTTP $status)"
        // povo-core unwraps the `error` and `result` envelopes itself, so this
        // is already the server's own text rather than a raw JSON body.
        else -> serverMessage.ifBlank { "エラー (HTTP $status)" }
    }
}
