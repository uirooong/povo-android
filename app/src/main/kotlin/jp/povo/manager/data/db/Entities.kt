package jp.povo.manager.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An account as the app knows it.
 *
 * Deliberately holds no credentials: the auth token and device id live in the
 * Keystore-encrypted [jp.povo.manager.core.SessionStore]. Room is a cache of
 * things that are merely private, not secret, so that the list can render
 * instantly and offline.
 */
@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val label: String?,
    val email: String?,
    val phoneNo: String?,
    val sin: String?,
    val externalId: String?,
    val planName: String?,
    val status: String?,
    /** 名義 — the contractor's name on the line. */
    val customerName: String? = null,
    /**
     * 開通日 as `YYYY-MM-DD`.
     *
     * Immutable once known, which is why the refresh only pays for the extra
     * request that carries it while this is still null.
     */
    val activationDate: String? = null,
    /** `YYYY-MM-DD` — the password for this account's invoice PDFs. */
    val birthDate: String?,
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    /**
     * When the billing timeline and purchase history were last *asked for* —
     * not when they last changed, and not null just because the account has no
     * invoices yet. The periodic refresh throttles those two endpoints off this
     * timestamp, so it has to record the attempt rather than the result.
     */
    val billsFetchedAt: Long? = null,
    val lastError: String? = null,
    /**
     * Epoch millis from a `block_until` in an API error. Until it passes, this
     * account is skipped rather than retried — the service asks clients to back
     * off after a 503 and hammering it would be both rude and useless.
     */
    val blockedUntil: Long? = null,
)

/**
 * The most recent usage reading for one account.
 *
 * [rawJson] is kept alongside the parsed columns on purpose. The payload is
 * undocumented and changes without notice, so when a field turns out to have
 * been misread there is something to re-parse instead of a lost reading.
 */
@Entity(tableName = "usage_snapshots")
data class UsageSnapshotEntity(
    @PrimaryKey val accountId: String,
    val fetchedAt: Long,
    val totalLeftKb: Double,
    val totalUsedKb: Double,
    /** Serialised `List<DataBucket>`. */
    val bucketsJson: String,
    val promotionLine1: String?,
    val promotionLine2: String?,
    val rawJson: String,
)

@Entity(tableName = "bills", primaryKeys = ["accountId", "rowKey"])
data class BillEntity(
    val accountId: String,
    /** `billId` when the invoice is issued, otherwise a synthetic key. */
    val rowKey: String,
    val billId: String?,
    val title: String?,
    val subtitle: String?,
    val status: String,
    val amountValue: Double?,
    val amountPrefix: String?,
    val timeEpochMillis: Long?,
    val hasPdf: Boolean,
)

/**
 * Screen-derived extras for one account: the subscribed toppings and the order
 * history, both of which come from Quilt pages.
 *
 * Stored as JSON rather than normalised tables because the values are display
 * strings the service already formatted (`55.45GB / 120.00GB`, `21,100円`) —
 * there is nothing to query or aggregate over, only to render.
 */
@Entity(tableName = "account_extras")
data class AccountExtrasEntity(
    @PrimaryKey val accountId: String,
    val fetchedAt: Long,
    /** Serialised `List<Topping>`. */
    val toppingsJson: String,
    /** Serialised `List<Purchase>`. */
    val purchasesJson: String,
)
