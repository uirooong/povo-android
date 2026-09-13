package jp.povo.manager.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One account's persisted session.
 *
 * `deviceId` is part of the session, not a device-wide value: the server binds
 * OTP and device registration to it, so it must be restored alongside the token
 * or subsequent calls look like they come from a different device.
 */
@Serializable
data class PovoSession(
    val accountId: String,
    val deviceId: String,
    val authToken: String,
    val sin: String? = null,
    val email: String? = null,
    val phoneNo: String? = null,
    val externalId: String? = null,
    val label: String? = null,
) {
    val isExpired: Boolean get() = Jwt.isExpired(authToken)
}

/**
 * Stores sessions for every account, encrypted as a single Keystore-wrapped
 * blob.
 *
 * Kept as one blob rather than a row per account because the whole set is
 * small, always read together at startup, and a single write keeps the accounts
 * consistent with each other.
 *
 * **Every change to that blob is serialised.** One blob means changing one
 * account is a read-modify-write over all of them, and accounts are refreshed
 * in parallel — so two unsynchronised renewals interleave and the later write
 * puts back the token the earlier one had just replaced. Nothing looks wrong at
 * the time, because each account's in-memory client still holds its own good
 * token; the loss only surfaces on the next launch, as one account out of
 * several demanding a fresh login. Accounts added together expire together,
 * which is what makes the overlap likely rather than rare.
 *
 * The lock is shared by every instance, because the file is: the repository and
 * the protocol screen each build their own store over the same blob.
 */
class SessionStore internal constructor(private val blobs: BlobStore) {

    constructor(context: Context) : this(SecureBlobStore(context, FILE_NAME))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<PovoSession> = synchronized(LOCK) { read() }

    fun save(sessions: List<PovoSession>) = synchronized(LOCK) { write(sessions) }

    /** Inserts or replaces one account's session, leaving the others untouched. */
    fun upsert(session: PovoSession) = synchronized(LOCK) {
        write(read().filterNot { it.accountId == session.accountId } + session)
    }

    fun remove(accountId: String) = synchronized(LOCK) {
        write(read().filterNot { it.accountId == accountId })
    }

    fun clear() = synchronized(LOCK) { blobs.clear() }

    private fun read(): List<PovoSession> {
        val raw = blobs.read() ?: return emptyList()
        return runCatching { json.decodeFromString<List<PovoSession>>(raw) }.getOrDefault(emptyList())
    }

    private fun write(sessions: List<PovoSession>) {
        if (sessions.isEmpty()) blobs.clear() else blobs.write(json.encodeToString(sessions))
    }

    private companion object {
        const val FILE_NAME = "sessions.bin"

        /**
         * Guards the one blob every instance shares. A lock held per instance
         * would leave exactly the two-writer case it exists to prevent.
         */
        val LOCK = Any()
    }
}
