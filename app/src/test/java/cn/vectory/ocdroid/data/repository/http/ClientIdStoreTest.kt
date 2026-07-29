package cn.vectory.ocdroid.data.repository.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * §B2 (slimapi-v2-adapt-traffic-plan §B): [InMemoryClientIdStore] 单测.
 *
 * 验证 [EspClientIdStore] 的 get-or-create + override 算法（两者共用相同
 * synchronized 临界区 + 同样的 override 解析逻辑；ESP 仅是持久化后端，算法
 * 行为对等——与 [InMemoryTofuPinStore] / [EspTofuPinStore] 的测试拆分同模式）。
 *
 * 覆盖：
 *  - 稳定性：多次调用返回同一 id。
 *  - 原子 get-or-create：并发首次访问仅生成 1 个 id（不产生两个）。
 *  - 覆盖路径：override 非空 → 用 override；override 空/清除 → 回退随机 id。
 */
class ClientIdStoreTest {

    // ── 稳定性 ──────────────────────────────────────────────────────────

    @Test
    fun `getDeviceId stable across calls`() {
        val store = InMemoryClientIdStore()
        val first = store.getDeviceId()
        assertNotNull(first)
        val second = store.getDeviceId()
        assertEquals("device id MUST be stable across calls", first, second)
        assertEquals(first, store.getDeviceId())
    }

    @Test
    fun `getDeviceId is a valid UUIDv4`() {
        val store = InMemoryClientIdStore()
        val id = store.getDeviceId()
        assertNotNull(id)
        // Must be a parseable UUIDv4 (random) — pins the format contract.
        val uuid = UUID.fromString(id)
        // RFC 4122 variant (the IETF variant, value 2).
        assertEquals(2, uuid.variant())
        // Version 4 = randomly generated.
        assertEquals(4, uuid.version())
    }

    @Test
    fun `seeded device id is returned verbatim`() {
        val store = InMemoryClientIdStore(seedDeviceId = "preset-id-123")
        assertEquals("preset-id-123", store.getDeviceId())
    }

    @Test
    fun `two fresh stores produce distinct ids`() {
        val a = InMemoryClientIdStore().getDeviceId()
        val b = InMemoryClientIdStore().getDeviceId()
        assertNotNull(a); assertNotNull(b)
        assertNotEquals("distinct stores MUST have distinct random ids", a, b)
    }

    // ── 原子 get-or-create（并发） ──────────────────────────────────────

    /**
     * §B2 atomicity: two concurrent first-access calls MUST observe exactly
     * one created id. A naive read-then-write without synchronization would
     * let both threads see "absent", both generate a UUID, and the second
     * would clobber the first → two distinct ids returned to the two callers.
     *
     * The [EspClientIdStore.lock] / [InMemoryClientIdStore] synchronized
     * block serializes the RMW so the second caller observes the first's write.
     */
    @Test
    fun `concurrent first access yields a single shared id`() {
        val store = InMemoryClientIdStore()
        val ready = CountDownLatch(1)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val idA = AtomicReference<String?>(null)
        val idB = AtomicReference<String?>(null)

        val t1 = Thread {
            ready.countDown()
            start.await(5, TimeUnit.SECONDS)
            idA.set(store.getDeviceId())
            done.countDown()
        }
        val t2 = Thread {
            ready.countDown()
            start.await(5, TimeUnit.SECONDS)
            idB.set(store.getDeviceId())
            done.countDown()
        }
        t1.start(); t2.start()
        // Both threads parked at the barrier before either has called getDeviceId.
        assertTrue("threads failed to park at barrier", ready.await(5, TimeUnit.SECONDS))
        // Release both simultaneously to maximize the race window.
        start.countDown()
        assertTrue("threads did not finish", done.await(5, TimeUnit.SECONDS))

        val a = idA.get()
        val b = idB.get()
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(
            "concurrent first-access MUST produce a single shared id (no double-create)",
            a, b
        )
    }

    @Test
    fun `many concurrent first accesses all observe one id`() {
        val store = InMemoryClientIdStore()
        val n = 16
        val start = CountDownLatch(1)
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        val threads = (1..n).map {
            Thread {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                store.getDeviceId()?.let(ids::add)
                done.countDown()
            }
        }
        threads.forEach(Thread::start)
        assertTrue("threads failed to park", ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue("threads did not finish", done.await(5, TimeUnit.SECONDS))

        assertEquals(
            "all $n concurrent callers MUST observe exactly one id",
            1, ids.size
        )
    }

    // ── 覆盖路径 ────────────────────────────────────────────────────────

    @Test
    fun `override set and non-blank uses override`() {
        val store = InMemoryClientIdStore(seedDeviceId = "random-uuid")
        store.setOverride("my-custom-id")
        assertEquals("override wins", "my-custom-id", store.getDeviceId())
    }

    @Test
    fun `override blank falls back to random`() {
        val store = InMemoryClientIdStore(seedDeviceId = "random-uuid")
        store.setOverride("   ")
        assertEquals(
            "blank override → fall back to persisted random UUID",
            "random-uuid", store.getDeviceId()
        )
    }

    @Test
    fun `override null clears and falls back to random`() {
        val store = InMemoryClientIdStore(seedDeviceId = "random-uuid")
        store.setOverride("temp")
        assertEquals("temp", store.getDeviceId())
        store.setOverride(null)
        assertEquals(
            "null override clears → fall back to persisted random UUID",
            "random-uuid", store.getDeviceId()
        )
    }

    @Test
    fun `override does not clobber the persisted random id`() {
        val store = InMemoryClientIdStore()
        val randomId = store.getDeviceId()
        assertNotNull(randomId)
        store.setOverride("override-id")
        assertEquals("override-id", store.getDeviceId())
        // Clearing the override MUST reveal the ORIGINAL random id, not a new one.
        store.setOverride(null)
        assertEquals(
            "clearing override restores the original persisted id (no regenerate)",
            randomId, store.getDeviceId()
        )
    }

    @Test
    fun `override blank does not clobber the persisted random id`() {
        val store = InMemoryClientIdStore()
        val randomId = store.getDeviceId()
        // Setting a blank override must NOT shadow the random id (the store
        // treats blank as "clear override" → fall back to persisted UUID).
        store.setOverride("")
        assertEquals(
            "blank override must fall back to the persisted random id",
            randomId, store.getDeviceId()
        )
    }

    // ── default device id absent (interface contract) ──────────────────

    @Test
    fun `InMemoryClientIdStore never returns null on fresh store`() {
        // The in-memory random-UUID path always creates a value; null is only
        // for the pathological ESP-write-failure case (EspClientIdStore).
        val store = InMemoryClientIdStore()
        assertNotNull(store.getDeviceId())
    }

    @Test
    fun `setOverride stored and retrieved via getDeviceId round trip`() {
        val store = InMemoryClientIdStore()
        store.setOverride("round-trip-id")
        assertEquals("round-trip-id", store.getDeviceId())
        store.setOverride("new-id")
        assertEquals("new-id", store.getDeviceId())
    }
}
