package jp.povo.manager.data

import jp.povo.manager.core.PovoSession

/**
 * Caches one long-lived object per account, discarding it when the session's
 * device id changes.
 *
 * The device id is the reason this is not a plain map. A client is built around
 * one device id and cannot be re-pointed at another, while the account id it is
 * keyed by is the reader's email address — stable across a re-login, which
 * mints a *new* device id. Keyed on account id alone, a re-login therefore
 * hands back the previous client, and every later request goes out with the old
 * device id against the new token. The service binds the two together and
 * answers **403001** to the mismatch.
 *
 * That failure also persists. A stale client renewing its own token writes the
 * result into the session that carries the new device id, so the stored pair is
 * permanently inconsistent and survives a restart — and because a 403 prompts a
 * re-login, the re-login recreates the mismatch. Comparing the device id here
 * is what breaks that cycle.
 */
internal class SessionScopedCache<T : AutoCloseable>(
    private val factory: (PovoSession) -> T,
) {
    private class Entry<T>(val deviceId: String, val value: T)

    private val entries = mutableMapOf<String, Entry<T>>()

    /**
     * The cached object for [session], rebuilt if it was made for a different
     * device id.
     */
    @Synchronized
    fun get(session: PovoSession): T {
        entries[session.accountId]?.let { existing ->
            if (existing.deviceId == session.deviceId) return existing.value
            // Closed rather than dropped: these hold native handles.
            existing.value.close()
        }
        return factory(session).also {
            entries[session.accountId] = Entry(session.deviceId, it)
        }
    }

    @Synchronized
    fun remove(accountId: String) {
        entries.remove(accountId)?.value?.close()
    }

    /** The device id the cached object was built with, for tests and logging. */
    @Synchronized
    fun deviceIdOf(accountId: String): String? = entries[accountId]?.deviceId
}
