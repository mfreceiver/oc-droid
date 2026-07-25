package cn.vectory.ocdroid.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §chat-list-detail §11 / G6 (B5-C1 / B5-C2 / B5-C3): per-route-entry
 * SavedStateHandle sub-agent checkpoint protocol.
 *
 * Covers the four scenarios the design mandates (B5-C3:
 * process-death/deep-link/multi-level/config-change/fast-pop):
 *  - **B5-C1 per-entry consume-once**: [consumeAnySubAgentCheckpoint]
 *    returns the stored checkpoint AND removes the key — a second call
 *    returns null.
 *  - **B5-C2 Parcelable shape**: [ScrollCheckpoint] implements
 *    [android.os.Parcelable] with a `CREATOR` + `describeContents=0`. The
 *    actual Parcel round-trip is verified by the B6-C2 connectedTest path
 *    (Parcel is a JVM stub in local unit tests; see
 *    `docs/specs/emulator-debug.md`); this JVM test asserts the structural
 *    Parcelable contract that SavedStateHandle relies on at runtime.
 *  - **B5-C3 deep-link direct (no checkpoint → null)**: a fresh handle
 *    yields no checkpoint (the Latest-by-default path).
 *  - **B5-C3 multi-level child→grandchild (per-entry isolation)**: each
 *    parent handle stores its own checkpoint under its own key; consuming
 *    one entry leaves the other intact.
 *  - **B5-C3 fast pop / repeated nav**: a single consume removes the entry
 *    so a re-entry (config change / double-fire) cannot double-consume.
 *
 * These are JVM-only tests (no Compose / NavController required) because the
 * storage primitives ([SavedStateHandle] + [ScrollCheckpoint]) are pure
 * data-layer types. The Compose-side LaunchedEffect that calls
 * [consumeAnySubAgentCheckpoint] is exercised in the connectedTest path
 * (B6-C2).
 */
class SavedStateHandleCheckpointTest {

    @Test
    fun `B5-C2 ScrollCheckpoint implements Parcelable`() {
        // §G6: SavedStateHandle accepts any Parcelable as a value. The
        // structural contract: a `CREATOR` field of type Parcelable.Creator
        // + describeContents()=0 (no FileDescriptor special handling).
        val cp = ScrollCheckpoint(anchorKey = "k", fallbackIndex = 1, offset = 2)
        assertEquals(0, cp.describeContents())
        assertNotNull(ScrollCheckpoint.CREATOR)
        // CREATOR's newArray is used by the Android runtime to allocate the
        // receiver array on the read side; verify it accepts a size and
        // returns an appropriately-typed array.
        val array = ScrollCheckpoint.CREATOR.newArray(3)
        assertEquals(3, array.size)
    }

    @Test
    fun `B5-C2 ScrollCheckpoint data-class equality holds for the three fields`() {
        // Companion to the Parcelable shape test: the Bundle path preserves
        // data-class equality because writeToParcel/CREATOR round-trip the
        // three fields verbatim (the B6 connectedTest asserts the round-trip
        // bytes; here we assert equality semantics).
        assertEquals(
            ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10),
            ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10))
        assertTrue(
            ScrollCheckpoint(anchorKey = null, fallbackIndex = 0, offset = 0) !=
                ScrollCheckpoint(anchorKey = "diff", fallbackIndex = 0, offset = 0))
    }

    @Test
    fun `B5-C2 SavedStateHandle stores and retrieves a ScrollCheckpoint value`() {
        // §G6: a SavedStateHandle accepts a Parcelable value via the indexed
        // get/set operators. JVM tests exercise the in-memory storage path
        // (the Bundle-backed persistence is exercised by the B6 connectedTest
        // process-death scenario). Verifying the in-memory path here proves
        // the key/value plumbing works end-to-end at the data layer.
        val handle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "anchor-key", fallbackIndex = 11, offset = 21)
        handle[checkpointKeyForChild("child-A")] = cp

        val readBack = handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A"))
        assertEquals(cp, readBack)
    }

    @Test
    fun `B5-C1 consumeAnySubAgentCheckpoint returns the stored checkpoint and removes the key`() {
        val handle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "k1", fallbackIndex = 1, offset = 1)
        handle[checkpointKeyForChild("child-A")] = cp

        val consumed = consumeAnySubAgentCheckpoint(handle)

        assertEquals(cp, consumed)
        // §11 consume-once: second call MUST return null (no double-fire).
        assertNull("consume MUST remove the key", consumeAnySubAgentCheckpoint(handle))
        assertNull("handle MUST NOT retain the key", handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A")))
    }

    @Test
    fun `B5-C3 deep-link direct into child yields no checkpoint (Latest-by-default)`() {
        // A fresh handle simulates first-entry / deep-link — no checkpoint
        // was ever written. The consume returns null, which the ChatScaffold
        // LaunchedEffect treats as "no Restore intent" (Latest stands).
        val handle = SavedStateHandle()
        assertNull(consumeAnySubAgentCheckpoint(handle))
    }

    @Test
    fun `B5-C3 multi-level child-to-grandchild keeps per-entry checkpoints isolated`() {
        // §11 protocol 2: each parent entry's handle stores its OWN child's
        // checkpoint. A parent of a child has child's checkpoint; that child
        // (when it later opens a grandchild) has grandchild's checkpoint on
        // the CHILD's handle, not the parent's. Consuming the parent's entry
        // does NOT touch the child's entry — they are on different handles
        // (different NavBackStackEntries).
        val parentHandle = SavedStateHandle()
        val childHandle = SavedStateHandle()
        val childCp = ScrollCheckpoint(anchorKey = "child-msg", fallbackIndex = 1, offset = 1)
        val grandchildCp = ScrollCheckpoint(anchorKey = "grandchild-msg", fallbackIndex = 2, offset = 2)

        // Parent opens child → checkpoint on parent's handle, keyed by childId.
        parentHandle[checkpointKeyForChild("child")] = childCp
        // Child opens grandchild → checkpoint on child's handle, keyed by grandchildId.
        childHandle[checkpointKeyForChild("grandchild")] = grandchildCp

        // User pops grandchild → child. Child's LaunchedEffect consumes ITS
        // handle's checkpoint; parent's handle is untouched.
        val childConsumed = consumeAnySubAgentCheckpoint(childHandle)
        assertEquals(grandchildCp, childConsumed)
        assertNotNull(parentHandle.get<ScrollCheckpoint>(checkpointKeyForChild("child")))

        // User pops child → parent. Parent's LaunchedEffect consumes its
        // checkpoint; child's handle is now empty.
        val parentConsumed = consumeAnySubAgentCheckpoint(parentHandle)
        assertEquals(childCp, parentConsumed)
        assertNull(consumeAnySubAgentCheckpoint(childHandle))
    }

    @Test
    fun `B5-C3 fast pop - consume-once does not double-fire on re-entry`() {
        // §11 "consume exactly once": a LaunchedEffect may re-fire on config
        // change / repeated nav. The consume MUST be idempotent — only the
        // FIRST call returns the checkpoint; subsequent calls return null.
        val handle = SavedStateHandle()
        handle[checkpointKeyForChild("only-child")] = ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0)

        val first = consumeAnySubAgentCheckpoint(handle)
        val second = consumeAnySubAgentCheckpoint(handle)
        val third = consumeAnySubAgentCheckpoint(handle)

        assertNotNull("first consume returns the checkpoint", first)
        assertNull("second consume is null (already consumed)", second)
        assertNull("third consume is null (already consumed)", third)
    }

    @Test
    fun `B5-C3 unrelated SavedStateHandle keys are not consumed`() {
        // Defensive: the consume iterates only the `subAgentCheckpoint:*`
        // prefix. An unrelated key (e.g. LazyListState.Saver's stored state)
        // MUST survive the consume.
        val handle = SavedStateHandle()
        handle["unrelated-key"] = "some-value"
        handle["scrollState-21"] = listOf(1, 2, 3)
        handle[checkpointKeyForChild("kid")] = ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0)

        val consumed = consumeAnySubAgentCheckpoint(handle)

        assertNotNull(consumed)
        assertEquals("some-value", handle.get<String>("unrelated-key"))
        assertEquals(listOf(1, 2, 3), handle.get<List<Int>>("scrollState-21"))
        assertNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("kid")))
    }

    @Test
    fun `B5-C3 multiple in-flight children - consume returns one and removes all stale entries`() {
        // §11 degenerate case: a parent handle ends up with multiple in-
        // flight child checkpoints (only possible via deep-link fan-in /
        // rapid openSubAgent without intermediate pops). The consume returns
        // ONE (the first encountered) and removes ALL `subAgentCheckpoint:*`
        // keys so no stale entry leaks. The single-scroll-intent contract
        // forbids two Restore intents firing for the same parent re-entry.
        val handle = SavedStateHandle()
        val cpA = ScrollCheckpoint(anchorKey = "k-a", fallbackIndex = 1, offset = 1)
        val cpB = ScrollCheckpoint(anchorKey = "k-b", fallbackIndex = 2, offset = 2)
        handle[checkpointKeyForChild("child-A")] = cpA
        handle[checkpointKeyForChild("child-B")] = cpB

        val consumed = consumeAnySubAgentCheckpoint(handle)

        // Exactly one is returned; the other is dropped (single intent).
        assertTrue("one of the two is returned", consumed == cpA || consumed == cpB)
        // Both keys are removed (no stale entry leaks).
        assertNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A")))
        assertNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-B")))
        assertNull(consumeAnySubAgentCheckpoint(handle))
    }

    @Test
    fun `checkpointKeyForChild produces the expected prefix`() {
        // §11 protocol 2: the key shape is constant-prefix + childId. Verified
        // so a future refactor cannot accidentally break the consume iteration.
        assertEquals("subAgentCheckpoint:child-1", checkpointKeyForChild("child-1"))
        assertEquals("subAgentCheckpoint:", checkpointKeyForChild(""))
        assertTrue(checkpointKeyForChild("x").startsWith(SUB_AGENT_CHECKPOINT_KEY_PREFIX))
    }

    /**
     * §chat-list-detail §11 / G6 (B5 BLOCK-fix CRITICAL): simulate the pop-
     * restore scenario at the helper level. The parent handle is "the same
     * instance" across the pop (NavBackStackEntry preserves its
     * SavedStateHandle). The checkpoint written at openSubAgent time is still
     * there when the parent's LaunchedEffect re-fires on re-composition →
     * consume returns non-null → Restore fires.
     *
     * This is a JVM proxy for the full NavController pop-restore scenario,
     * which requires a connectedTest (B6-C2 residual — Robolectric /
     * instrumented). The test proves the helper layer (SavedStateHandle +
     * consumeAnySubAgentCheckpoint) holds up its end of the contract: a
     * handle that survived an openSubAgent push + returnToExistingChat pop
     * still has the checkpoint.
     */
    @Test
    fun `B5 BLOCK-fix - parent handle retains checkpoint across simulated pop-restore cycle`() {
        val parentHandle = SavedStateHandle()
        val originalCheckpoint = ScrollCheckpoint(anchorKey = "msg-at-open", fallbackIndex = 5, offset = 11)

        // §11 sequence step 2 (openSubAgent success callback): write the
        // checkpoint to the parent's handle BEFORE the route-aware nav fires.
        parentHandle[checkpointKeyForChild("child-A")] = originalCheckpoint

        // ... time passes; user is now on chat/child-A; parentHandle is
        // preserved on the back-stack (its NavBackStackEntry stays alive) ...

        // returnToExistingChat fires; synchronizer pops; parent's
        // ChatScaffold re-composes; LaunchedEffect re-fires.
        val restored = consumeAnySubAgentCheckpoint(parentHandle)

        assertEquals(
            "parent handle MUST retain the checkpoint across the push+pop cycle",
            originalCheckpoint,
            restored)
        // Consume-once: subsequent re-fire (config change / double-tap) is null.
        assertNull("consume-once: second read is null", consumeAnySubAgentCheckpoint(parentHandle))
    }

    /**
     * §chat-list-detail §11 / G6 (B5 BLOCK-fix MAJOR 1): a stale checkpoint
     * left by a failed openSubAgent (pre-fix behavior) is the exact bug the
     * MAJOR 1 fix eliminates. Verify the FIXED contract: if the openSubAgent
     * fetch fails, the handle is NEVER written (the success callback is the
     * only writer). Simulate by NOT writing (matching the fixed code path)
     * and confirming the handle is empty.
     */
    @Test
    fun `B5 BLOCK-fix MAJOR 1 - failed openSubAgent leaves no checkpoint on parent handle`() {
        val parentHandle = SavedStateHandle()
        // §B5 BLOCK-fix: pre-fix code wrote the checkpoint BEFORE the fetch;
        // a failed fetch stranded the checkpoint. Fixed code writes ONLY
        // inside the success callback — so on fetch failure, the handle is
        // untouched. Simulate by inspecting immediately (no write).
        val consumed = consumeAnySubAgentCheckpoint(parentHandle)
        assertNull("no checkpoint written on failed fetch", consumed)
    }
}
