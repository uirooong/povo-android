package jp.povo.manager.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression test for accounts that quietly lost their renewed token.
 *
 * All sessions live in one blob, so changing one account is a read-modify-write
 * over all of them — and accounts are refreshed in parallel. Unsynchronised,
 * two renewals interleave and the later write restores the token the earlier
 * one had just replaced. The symptom is delayed and partial: the in-memory
 * client still holds the good token, so nothing fails until the next launch,
 * when one account out of several asks to log in again.
 */
class SessionStoreTest {

    /**
     * Stands in for the encrypted file, and widens the window between read and
     * write so the race is hit every run rather than occasionally.
     */
    private class SlowBlobStore(private val delayMillis: Long = 5) : BlobStore {
        @Volatile
        private var contents: String? = null

        override fun read(): String? {
            Thread.sleep(delayMillis)
            return contents
        }

        override fun write(plaintext: String) {
            Thread.sleep(delayMillis)
            contents = plaintext
        }

        override fun clear() {
            contents = null
        }
    }

    private fun session(id: String, token: String) =
        PovoSession(accountId = id, deviceId = "device-$id", authToken = token)

    @Test
    fun `concurrent renewals all survive`() {
        val store = SessionStore(SlowBlobStore())
        val ids = (1..5).map { "account-$it" }
        // The starting state: five accounts, as if added together.
        ids.forEach { store.upsert(session(it, "old")) }

        // Renewed at the same moment, which is what accounts added together do
        // — their tokens expire together too.
        val pool = Executors.newFixedThreadPool(ids.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(ids.size)
        ids.forEach { id ->
            pool.execute {
                start.await()
                store.upsert(session(id, "renewed"))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("renewals did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        val stored = store.load()
        assertEquals(ids.size, stored.size)
        // Not one of them may have been reverted: a stale token is expired, and
        // the service will not renew from it.
        assertEquals(emptyList<String>(), stored.filter { it.authToken != "renewed" }.map { it.accountId })
    }

    @Test
    fun `a removal during renewals does not take others with it`() {
        val store = SessionStore(SlowBlobStore())
        val ids = (1..4).map { "account-$it" }
        ids.forEach { store.upsert(session(it, "old")) }

        val pool = Executors.newFixedThreadPool(ids.size)
        val start = CountDownLatch(1)
        val done = CountDownLatch(ids.size)
        ids.forEach { id ->
            pool.execute {
                start.await()
                if (id == "account-1") store.remove(id) else store.upsert(session(id, "renewed"))
                done.countDown()
            }
        }
        start.countDown()
        assertTrue("work did not finish", done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        val stored = store.load()
        assertEquals(listOf("account-2", "account-3", "account-4"), stored.map { it.accountId }.sorted())
        assertTrue(stored.all { it.authToken == "renewed" })
    }

    @Test
    fun `an unreadable blob reads as no accounts rather than throwing`() {
        // The Keystore key is destroyed when device credentials change, and the
        // right answer to that is an empty list and a fresh login.
        val broken = object : BlobStore {
            override fun read() = "not json"
            override fun write(plaintext: String) = Unit
            override fun clear() = Unit
        }
        assertEquals(emptyList<PovoSession>(), SessionStore(broken).load())
    }

    @Test
    fun `emptying clears the blob rather than storing an empty list`() {
        val store = SessionStore(SlowBlobStore(delayMillis = 0))
        store.upsert(session("only", "t"))

        store.remove("only")

        assertEquals(emptyList<PovoSession>(), store.load())
    }
}
