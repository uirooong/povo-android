package jp.povo.manager.data

import jp.povo.manager.core.PovoSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rule that a client belongs to one device id.
 *
 * This is a regression test for a real failure: accounts that could not refresh
 * and answered 403001 forever. The account id is the reader's email address, so
 * it survives a re-login while the device id does not, and a cache keyed on the
 * account alone kept handing back the previous device's client. Every request
 * then carried the old device id with the new token — the exact pair the
 * service rejects.
 */
class SessionScopedCacheTest {

    private class FakeClient(val deviceId: String) : AutoCloseable {
        var closed = false
        override fun close() { closed = true }
    }

    private fun session(accountId: String, deviceId: String) =
        PovoSession(accountId = accountId, deviceId = deviceId, authToken = "t")

    @Test
    fun `reuses the client while the device id is unchanged`() {
        val cache = SessionScopedCache { FakeClient(it.deviceId) }
        val first = cache.get(session("a@example.com", "dev-1"))

        // A renewed token must not cost a new client: the client owns the live
        // token itself, and rebuilding on every call would defeat the cache.
        val again = cache.get(session("a@example.com", "dev-1").copy(authToken = "t2"))

        assertSame(first, again)
        assertFalse(first.closed)
    }

    @Test
    fun `rebuilds when a re-login changes the device id`() {
        val cache = SessionScopedCache { FakeClient(it.deviceId) }
        val old = cache.get(session("a@example.com", "dev-1"))

        // Same account id — it is the email address — but a login mints a new
        // device id. This is the case the old cache got wrong.
        val fresh = cache.get(session("a@example.com", "dev-2"))

        assertNotSame(old, fresh)
        assertEquals("dev-2", fresh.deviceId)
        assertEquals("dev-2", cache.deviceIdOf("a@example.com"))
        // The displaced one holds a native handle; it has to be released.
        assertTrue(old.closed)
    }

    @Test
    fun `keeps accounts apart`() {
        val cache = SessionScopedCache { FakeClient(it.deviceId) }
        val a = cache.get(session("a@example.com", "dev-a"))
        val b = cache.get(session("b@example.com", "dev-b"))

        assertNotSame(a, b)
        assertSame(a, cache.get(session("a@example.com", "dev-a")))
        assertFalse(a.closed)
    }

    @Test
    fun `removing closes and forgets`() {
        val cache = SessionScopedCache { FakeClient(it.deviceId) }
        val first = cache.get(session("a@example.com", "dev-1"))

        cache.remove("a@example.com")

        assertTrue(first.closed)
        assertEquals(null, cache.deviceIdOf("a@example.com"))
        assertNotSame(first, cache.get(session("a@example.com", "dev-1")))
        // Removing something absent is not an error; every teardown path calls it.
        cache.remove("nobody@example.com")
    }
}
