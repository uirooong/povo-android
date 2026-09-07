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
 */
class SessionStore(context: Context) {

    private val blobs = SecureBlobStore(context, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): List<PovoSession> {
        val raw = blobs.read() ?: return emptyList()
        return runCatching { json.decodeFromString<List<PovoSession>>(raw) }.getOrDefault(emptyList())
    }

    fun save(sessions: List<PovoSession>) {
        if (sessions.isEmpty()) blobs.clear() else blobs.write(json.encodeToString(sessions))
    }

    /** Inserts or replaces one account's session, leaving the others untouched. */
    fun upsert(session: PovoSession) {
        save(load().filterNot { it.accountId == session.accountId } + session)
    }

    fun remove(accountId: String) {
        save(load().filterNot { it.accountId == accountId })
    }

    fun clear() = blobs.clear()

    private companion object {
        const val FILE_NAME = "sessions.bin"
    }
}
