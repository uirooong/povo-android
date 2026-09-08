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
import jp.povo.manager.core.model.PovoWebPage
import jp.povo.manager.core.model.PovoWebPageKind
import jp.povo.manager.core.model.ProfileInfo
import jp.povo.manager.core.model.ToppingOrder
import jp.povo.manager.core.model.ToppingSection
import jp.povo.manager.core.model.PlanUsage
import jp.povo.manager.core.model.Purchase
import jp.povo.manager.core.model.Topping
import jp.povo.manager.core.json.QuiltParser
import jp.povo.manager.data.db.AccountEntity
import jp.povo.manager.data.db.AccountExtrasEntity
import jp.povo.manager.data.db.BillEntity
import jp.povo.manager.data.db.PovoDatabase
import jp.povo.manager.data.db.UsageSnapshotEntity
import jp.povo.manager.data.db.WebPageEntity
import jp.povo.manager.notify.SuspensionNotifier
import jp.povo.manager.widget.UsageDonutWidget
import jp.povo.manager.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import uniffi.povo_core.PovoException
import uniffi.povo_core.UserProfile
import java.time.LocalDate
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

    private val settings = SettingsStore(context)
    private val suspensions = SuspensionStore(context)

    /**
     * One client per account, rebuilt when the account's device id changes.
     *
     * See [SessionScopedCache] for why the device id has to be part of that
     * decision — reusing a client across a re-login is what produces 403001.
     */
    private val clients = SessionScopedCache { session ->
        PovoAccountClient.create(
            accountId = session.accountId,
            deviceId = session.deviceId,
            authToken = session.authToken,
            sin = session.sin,
        )
    }

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

    /** The povo pages this account has a way into, in no particular order. */
    fun observeWebPages(id: String): Flow<List<WebPageEntity>> = db.webPages().observe(id)
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
        clients.remove(id)
        sessions.remove(id)
        db.usage().delete(id)
        db.bills().deleteFor(id)
        db.extras().delete(id)
        db.webPages().deleteFor(id)
        db.accounts().delete(id)
        suspensions.setAnchor(id, null)
        SuspensionNotifier.cancel(context, id)
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

    /**
     * The stored link for one of povo's own pages, or null if this account's
     * profile page has not been read yet or did not offer it.
     */
    suspend fun webPage(id: String, kind: PovoWebPageKind): PovoWebPage? =
        db.webPages().get(id, kind.name)?.let {
            PovoWebPage(
                kind = kind,
                link = it.link,
                exitUrl = it.exitUrl,
                needsXauth = it.needsXauth,
            )
        }

    /**
     * The toppings this account can buy right now.
     *
     * Read live rather than cached: prices, campaigns and what is on offer are
     * the service's to decide minute by minute, and a stale price is the one
     * thing a purchase screen must never show.
     */
    suspend fun toppingCatalogue(id: String): List<ToppingSection> {
        val session = session(id) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            val client = clientFor(session)
            ensureFreshToken(client, session)
            QuiltParser.parseCatalogue(client.getQuiltPageJson(QUILT_DASHBOARD_PAGE))
        }
    }

    /**
     * Places an order for one topping.
     *
     * Returns the service's answer rather than a boolean, because a successful
     * call is not a completed purchase: an order needing 3-D Secure comes back
     * with a challenge page and nothing is charged until it is finished. The
     * caller decides what to do with that.
     */
    suspend fun orderTopping(id: String, sku: String): Result<ToppingOrder> {
        val session = session(id) ?: return Result.failure(IllegalStateException("アカウントが見つかりません"))
        return withContext(Dispatchers.IO) {
            runCatching {
                val client = clientFor(session)
                ensureFreshToken(client, session)
                val raw = client.placeToppingOrderJson(sku, PURCHASE_REDIRECT_URL)
                QuiltParser.parseOrder(raw)
                    ?: throw IllegalStateException("購入の応答を読み取れませんでした")
            }.onFailure { Log.w(TAG, "order failed for $id", it) }
        }
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
            }.awaitAll().also { notifyWidget(it); warnAboutSuspension() }
        }

    suspend fun refreshAccount(
        id: String,
        bills: BillsPolicy = BillsPolicy.ALWAYS,
    ): RefreshOutcome {
        val session = session(id) ?: return RefreshOutcome(id, RefreshResult.NOT_FOUND)
        return withContext(Dispatchers.IO) { refresh(session, bills) }
            .also { notifyWidget(listOf(it)); warnAboutSuspension() }
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

    /**
     * Raises the 180-day warning for any account close enough to its date.
     *
     * Placed beside [notifyWidget] and called from the same two points for the
     * same reason: every way of refreshing should check, not just the periodic
     * worker. It does not depend on the outcomes — the countdown moves with the
     * calendar, not with what the service said, so a day on which every request
     * failed is still a day closer.
     *
     * At most one notification per account per suspension date. The refresh
     * runs every fifteen minutes, so without that the same warning would arrive
     * ninety-six times a day; recording the date rather than a flag means
     * buying a topping and re-entering the anchor re-arms it.
     */
    private suspend fun warnAboutSuspension() {
        if (!settings.suspensionEnabled.first()) return
        if (!settings.suspensionNotifyEnabled.first()) return
        val within = settings.suspensionNotifyDays.first()

        val accounts = db.accounts().all().associateBy { it.id }
        val today = LocalDate.now()
        suspensions.anchors.first().forEach { (accountId, anchor) ->
            val account = accounts[accountId] ?: return@forEach
            val forecast = anchor.forecast(today) ?: return@forEach
            if (forecast.daysLeft > within) return@forEach
            if (anchor.notifiedFor == forecast.suspendsOn.toString()) return@forEach

            val posted = SuspensionNotifier.notify(
                context = context,
                accountId = accountId,
                label = account.label ?: account.phoneNo ?: accountId,
                forecast = forecast,
            )
            // Only recorded once it could actually have been seen: a warning
            // dropped for a missing permission must not silence tomorrow's.
            if (posted) suspensions.markNotified(accountId, forecast.suspendsOn)
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
            // Throttled with the billing endpoints: a registered card and the
            // links to povo's own pages change about as often as an invoice
            // does. Only the masked number and those links are taken from this
            // page — it also carries the contractor's name, postal address and
            // PIN mask, which the app has no use for and does not read.
            val profileInfo = if (!includeBills) null else runCatching {
                QuiltParser.parseProfile(client.getQuiltPageJson(QUILT_PROFILE_PAGE))
            }.getOrNull()

            persist(
                session, client, profile, usage, bills, toppings, purchases,
                billsAttempted = includeBills,
                profileInfo = profileInfo,
            )
            RefreshOutcome(session.accountId, RefreshResult.OK)
        } catch (e: PovoException) {
            val blockUntil = (e as? PovoException.Api)?.blockUntilEpochMillis
            // 403 counts as well as 401. A token is bound to the device id it
            // was issued with — presenting it with any other one answers
            // 403001, measured against a live account — and no amount of
            // retrying re-pairs them. Classing it as FAILED left the periodic
            // worker retrying a permanently broken line every fifteen minutes
            // while the list showed no way to fix it.
            val needsLogin = e is PovoException.Unauthenticated ||
                (e as? PovoException.Api)?.status?.toInt() in setOf(401, 403)
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
        // The renewed token is bound to the device id that asked for it, so
        // storing it against a session carrying a different one is what makes a
        // 403001 permanent. [clients] should never hand back a mismatched
        // client; this refuses to write the damage if it ever does.
        if (client.deviceId != session.deviceId) {
            Log.w(TAG, "device id mismatch for ${session.accountId}; not renewing")
            return
        }
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
        profileInfo: ProfileInfo? = null,
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
                paymentMasked = profileInfo?.paymentMasked ?: existing?.paymentMasked,
                lastRefreshedAt = now,
                // Records the attempt, not the outcome: an account with no
                // invoices parses to null, and treating that as "never fetched"
                // would defeat the throttle entirely.
                billsFetchedAt = if (billsAttempted) now else existing?.billsFetchedAt,
                lastError = null,
                blockedUntil = null,
            ),
        )

        // Replaced only when the page was read. A throttled run leaves the
        // stored links alone rather than clearing every entry point until the
        // next full refresh.
        profileInfo?.let { info ->
            db.webPages().replaceFor(
                session.accountId,
                info.webPages.map { page ->
                    WebPageEntity(
                        accountId = session.accountId,
                        kind = page.kind.name,
                        link = page.link,
                        exitUrl = page.exitUrl,
                        needsXauth = page.needsXauth,
                    )
                },
            )
        }

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

    private fun clientFor(session: PovoSession): PovoAccountClient = clients.get(session)

    /** Drops a cached client so the next call picks up a re-authenticated session. */
    fun invalidateClient(accountId: String) = clients.remove(accountId)

    companion object {
        private const val TAG = "PovoRepo"
        private const val EMPTY_JSON_ARRAY = "[]"
        private const val MAX_CONCURRENT_ACCOUNTS = 3
        private const val STAGGER_MAX_MILLIS = 250L
        private const val QUILT_PLAN_PAGE = "user-plan-details-v2"
        private const val QUILT_ORDERS_PAGE = "order-history"

        /**
         * Carries the purchasable catalogue as `addon-section` tiles.
         * `user-plan-details-v2` does not, despite the name — it reports only
         * what the line already holds.
         */
        private const val QUILT_DASHBOARD_PAGE = "dashboard-v2"

        /**
         * Where a 3-D Secure challenge lands when it succeeds.
         *
         * The official app's own value. The page is watched for rather than
         * loaded, so it only has to be a URL both sides agree on.
         */
        const val PURCHASE_REDIRECT_URL = "https://povo.jp/success"

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
        // Must keep saying 再ログイン: the accounts list decides whether to
        // offer that by looking for the word (AccountCard.needsLogin).
        403 -> "再ログインが必要です（端末の認証情報が無効です） / ${apiDetail()}"
        429 -> "リクエストが多すぎます。しばらく待ってください"
        else -> apiDetail()
    }
}

/**
 * Everything the service said, in a form someone can quote.
 *
 * The status alone is not enough. povo's own support asks for the numeric code,
 * and an account that fails to refresh is diagnosed by that code and nothing
 * else — the earlier version dropped it, and dropped the message entirely on a
 * 5xx, which left a failing line reporting only "HTTP 500".
 *
 * The message is shown even when it arrives as a raw JSON body. That is ugly:
 * povo-core unwraps the `error` and `result` envelopes but not `failure`, so
 * some bodies come through unopened (see `docs/POVO-CORE-REQUESTS.md` item 0).
 * Ugly beats hidden here — it is the only information there is, and the code
 * that identifies the failure may be inside it.
 */
private fun PovoException.Api.apiDetail(): String {
    val said = serverMessage.trim().takeIf(String::isNotEmpty)
        ?.let { if (it.length > MAX_SERVER_MESSAGE) it.take(MAX_SERVER_MESSAGE) + "…" else it }
    return listOfNotNull(
        said ?: if (status.toInt() >= 500) "povo 側でエラーが発生しています" else "エラー",
        code?.let { "コード $it" },
        title?.takeIf { it.isNotBlank() && it != said },
        "HTTP $status",
    ).joinToString(" / ")
}

/** Long enough for a sentence, short enough not to swallow a list row. */
private const val MAX_SERVER_MESSAGE = 160
