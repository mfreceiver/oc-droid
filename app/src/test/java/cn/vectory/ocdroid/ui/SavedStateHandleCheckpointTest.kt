package cn.vectory.ocdroid.ui

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §chat-list-detail §11 / G6 (B5) + §scroll-guard-fix: shared-SavedStateHandle
 * sub-agent checkpoint protocol.
 *
 * §scroll-guard-fix context: chat→chat navigation uses `launchSingleTop`, so
 * Navigation 2.8.x IN-PLACE REPLACES the single chat back-stack slot — parent
 * and child SHARE one SavedStateHandle, and nested sub-agents (P→C→G)
 * accumulate multiple `subAgentCheckpoint:*` keys on it. Direction
 * (enter-child vs return-to-parent vs nested vs unrelated-jump) is
 * disambiguated by each checkpoint's [ScrollCheckpoint.capturedFromSessionId]
 * vs the current session.
 *
 * Covers:
 *  - **Parcelable shape**: [ScrollCheckpoint] implements [android.os.Parcelable]
 *    (CREATOR + describeContents=0). Parcel round-trip is verified by the B6-C2
 *    connectedTest (Parcel is a JVM stub locally); here we assert the structural
 *    contract SavedStateHandle relies on.
 *  - **capturedFrom direction guard**: return-to-parent consumes; enter-child /
 *    nested / unrelated-jump skip and retain; null-origin (legacy) never consumed.
 *  - **nested lifecycle (P→C→G→C→P)**: each checkpoint consumed at its own
 *    return-to-capturing-parent; the other stays put on the shared handle.
 *  - **consume-once idempotence**: re-fires (config change / double-fire) no-op.
 *  - **unrelated keys / deep-link / failed-fetch**: defensive null paths.
 *
 * JVM-only (no Compose / NavController) — the storage primitives are pure
 * data-layer types. The Compose-side LaunchedEffect is exercised in the
 * connectedTest path (B6-C2).
 */
class SavedStateHandleCheckpointTest {

    // ── Parcelable shape + storage plumbing ───────────────────────────────

    @Test
    fun `ScrollCheckpoint implements Parcelable`() {
        val cp = ScrollCheckpoint(anchorKey = "k", fallbackIndex = 1, offset = 2)
        assertEquals(0, cp.describeContents())
        assertNotNull(ScrollCheckpoint.CREATOR)
        val array = ScrollCheckpoint.CREATOR.newArray(3)
        assertEquals(3, array.size)
    }

    @Test
    fun `ScrollCheckpoint data-class equality holds for all four fields`() {
        assertEquals(
            ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10, capturedFromSessionId = "P"),
            ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10, capturedFromSessionId = "P"))
        // capturedFromSessionId participates in equality.
        assertTrue(
            ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10, capturedFromSessionId = "P") !=
                ScrollCheckpoint(anchorKey = "msg-1", fallbackIndex = 5, offset = 10, capturedFromSessionId = "Q"))
        assertTrue(
            ScrollCheckpoint(anchorKey = null, fallbackIndex = 0, offset = 0) !=
                ScrollCheckpoint(anchorKey = "diff", fallbackIndex = 0, offset = 0))
    }

    @Test
    fun `SavedStateHandle stores and retrieves a ScrollCheckpoint value`() {
        val handle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "anchor-key", fallbackIndex = 11, offset = 21, capturedFromSessionId = "P")
        handle[checkpointKeyForChild("child-A")] = cp

        val readBack = handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A"))
        assertEquals(cp, readBack)
    }

    // ── §scroll-guard-fix: capturedFrom direction guard ───────────────────

    @Test
    fun `capturedFrom guard - return-to-parent consumes checkpoint captured by current session`() {
        val handle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "k1", fallbackIndex = 1, offset = 1, capturedFromSessionId = "parent")
        handle[checkpointKeyForChild("child-A")] = cp

        val consumed = consumeAnySubAgentCheckpoint(handle, "parent")
        assertEquals(cp, consumed)
        assertNull("consume removes the key", consumeAnySubAgentCheckpoint(handle, "parent"))
        assertNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A")))
    }

    @Test
    fun `capturedFrom guard - enter-child skips checkpoint captured by a different session`() {
        val handle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "k", fallbackIndex = 3, offset = 5, capturedFromSessionId = "parent")
        handle[checkpointKeyForChild("child-A")] = cp

        // Enter-child: current=child-A != capturedFrom=parent → skip + retain.
        assertNull(
            "enter-child MUST NOT consume a checkpoint captured by another session",
            consumeAnySubAgentCheckpoint(handle, "child-A"))
        assertEquals(
            "key MUST be retained for return-to-parent",
            cp, handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A")))
    }

    @Test
    fun `capturedFrom guard - legacy checkpoint with null origin is never consumed`() {
        // Backward-compat: a checkpoint persisted before capturedFromSessionId
        // existed reads back with capturedFromSessionId=null. The guard treats
        // null as "unknown origin" → never consumed (degrades to Latest). This
        // matches the pre-fix broken return-to-parent, so NO new regression.
        val handle = SavedStateHandle()
        handle[checkpointKeyForChild("child-A")] =
            ScrollCheckpoint(anchorKey = "k", fallbackIndex = 1, offset = 1, capturedFromSessionId = null)

        assertNull(
            "null-origin checkpoint is never consumed",
            consumeAnySubAgentCheckpoint(handle, "parent"))
        // Key left in place (harmless; a future write replaces it).
        assertNotNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("child-A")))
    }

    // ── §scroll-guard-fix: nested sub-agent scenarios (the rev-ogpt cases) ─

    @Test
    fun `capturedFrom guard - nested P to C to G enter-G skips both checkpoints`() {
        // Shared handle. P opened C (wrote :C{from:P}); C opened G (wrote :G{from:C}).
        // Entering G MUST consume neither — :C awaits P's return, :G awaits C's.
        val cpForC = ScrollCheckpoint(anchorKey = "c", fallbackIndex = 1, offset = 1, capturedFromSessionId = "P")
        val cpForG = ScrollCheckpoint(anchorKey = "g", fallbackIndex = 2, offset = 2, capturedFromSessionId = "C")
        val sharedHandle = SavedStateHandle()
        sharedHandle[checkpointKeyForChild("C")] = cpForC
        sharedHandle[checkpointKeyForChild("G")] = cpForG

        val consumed = consumeAnySubAgentCheckpoint(sharedHandle, "G")
        assertNull("enter-G MUST NOT consume any checkpoint", consumed)
        assertEquals(":C retained for P's return",
            cpForC, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
        assertEquals(":G retained for C's return",
            cpForG, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("G")))
    }

    @Test
    fun `capturedFrom guard - nested G to C return consumes only grandchild checkpoint`() {
        val cpForC = ScrollCheckpoint(anchorKey = "c", fallbackIndex = 1, offset = 1, capturedFromSessionId = "P")
        val cpForG = ScrollCheckpoint(anchorKey = "g", fallbackIndex = 2, offset = 2, capturedFromSessionId = "C")
        val sharedHandle = SavedStateHandle()
        sharedHandle[checkpointKeyForChild("C")] = cpForC
        sharedHandle[checkpointKeyForChild("G")] = cpForG

        // Return G→C: current=C → consume :G{from:C}, retain :C{from:P}.
        val consumed = consumeAnySubAgentCheckpoint(sharedHandle, "C")
        assertEquals(cpForG, consumed)
        assertEquals(":C{from:P} retained for P's return",
            cpForC, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
        assertNull(":G consumed", sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("G")))
    }

    @Test
    fun `capturedFrom guard - jump to unrelated session D skips and retains checkpoint`() {
        // C jumps to an unrelated session D (not via sub-agent — e.g. picker /
        // tab). handle still has :C{from:P}. current=D != from=P → skip + retain.
        // This is the regression rev-ogpt caught: a childId-based guard would
        // have consumed :C (C != D) and fed P's checkpoint to D as a wrong Restore.
        val handle = SavedStateHandle()
        val cpForC = ScrollCheckpoint(anchorKey = "c", fallbackIndex = 1, offset = 1, capturedFromSessionId = "P")
        handle[checkpointKeyForChild("C")] = cpForC

        val consumed = consumeAnySubAgentCheckpoint(handle, "D")
        assertNull("unrelated-jump MUST NOT consume a checkpoint captured by P", consumed)
        assertEquals("key MUST be retained for P's eventual return",
            cpForC, handle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
    }

    @Test
    fun `capturedFrom guard - full P to C to P cycle retains then consumes`() {
        val sharedHandle = SavedStateHandle()
        val cp = ScrollCheckpoint(anchorKey = "anchor", fallbackIndex = 7, offset = 13, capturedFromSessionId = "P")
        sharedHandle[checkpointKeyForChild("C")] = cp

        // Phase 1: enter-child C. capturedFrom=P != C → skip, retain.
        assertNull(consumeAnySubAgentCheckpoint(sharedHandle, "C"))
        assertEquals(cp, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))

        // Phase 2: return-to-parent P. capturedFrom=P == P → consume.
        assertEquals(cp, consumeAnySubAgentCheckpoint(sharedHandle, "P"))
        assertNull(sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
    }

    @Test
    fun `multi-level nested lifecycle on shared handle consumes each checkpoint at its own return`() {
        // Full P → C → G → C → P lifecycle on ONE shared handle (the real model
        // under launchSingleTop in-place replacement). Each checkpoint is
        // consumed exactly at its own return-to-capturing-parent; the other
        // stays put. This is the end-to-end coverage rev-ogpt asked for (the
        // prior per-entry-isolation test used two independent handles, which
        // did not match the shared-slot reality and masked the bug).
        val sharedHandle = SavedStateHandle()
        val cpForC = ScrollCheckpoint(anchorKey = "c", fallbackIndex = 1, offset = 1, capturedFromSessionId = "P")
        val cpForG = ScrollCheckpoint(anchorKey = "g", fallbackIndex = 2, offset = 2, capturedFromSessionId = "C")

        // Step 1: P opens C → write :C{from:P} → enter C.
        sharedHandle[checkpointKeyForChild("C")] = cpForC
        assertNull("enter C: :C{from:P} not consumed (from P != C)",
            consumeAnySubAgentCheckpoint(sharedHandle, "C"))
        assertEquals(cpForC, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))

        // Step 2: C opens G → write :G{from:C} → enter G.
        sharedHandle[checkpointKeyForChild("G")] = cpForG
        assertNull("enter G: neither consumed (from P/C != G)",
            consumeAnySubAgentCheckpoint(sharedHandle, "G"))
        assertEquals(cpForC, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
        assertEquals(cpForG, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("G")))

        // Step 3: return G→C. current=C → consume :G{from:C}, retain :C{from:P}.
        assertEquals("return to C consumes :G{from:C}",
            cpForG, consumeAnySubAgentCheckpoint(sharedHandle, "C"))
        assertEquals(":C{from:P} retained for P's return",
            cpForC, sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
        assertNull(":G consumed", sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("G")))

        // Step 4: return C→P. current=P → consume :C{from:P}.
        assertEquals("return to P consumes :C{from:P}",
            cpForC, consumeAnySubAgentCheckpoint(sharedHandle, "P"))
        assertNull(sharedHandle.get<ScrollCheckpoint>(checkpointKeyForChild("C")))
    }

    // ── consume-once idempotence + defensive paths ────────────────────────

    @Test
    fun `consume-once is idempotent across re-fires`() {
        // A LaunchedEffect may re-fire on config change / repeated nav. The
        // consume MUST be idempotent — only the FIRST call returns the
        // checkpoint; subsequent calls return null.
        val handle = SavedStateHandle()
        handle[checkpointKeyForChild("only-child")] =
            ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0, capturedFromSessionId = "parent")

        val first = consumeAnySubAgentCheckpoint(handle, "parent")
        val second = consumeAnySubAgentCheckpoint(handle, "parent")
        val third = consumeAnySubAgentCheckpoint(handle, "parent")

        assertNotNull("first consume returns the checkpoint", first)
        assertNull("second consume is null (already consumed)", second)
        assertNull("third consume is null (already consumed)", third)
    }

    @Test
    fun `unrelated SavedStateHandle keys are not consumed`() {
        // Defensive: the consume iterates only the `subAgentCheckpoint:*`
        // prefix. An unrelated key (e.g. LazyListState.Saver's stored state)
        // MUST survive the consume.
        val handle = SavedStateHandle()
        handle["unrelated-key"] = "some-value"
        handle["scrollState-21"] = listOf(1, 2, 3)
        handle[checkpointKeyForChild("kid")] =
            ScrollCheckpoint(anchorKey = "k", fallbackIndex = 0, offset = 0, capturedFromSessionId = "parent")

        val consumed = consumeAnySubAgentCheckpoint(handle, "parent")

        assertNotNull(consumed)
        assertEquals("some-value", handle.get<String>("unrelated-key"))
        assertEquals(listOf(1, 2, 3), handle.get<List<Int>>("scrollState-21"))
        assertNull(handle.get<ScrollCheckpoint>(checkpointKeyForChild("kid")))
    }

    @Test
    fun `deep-link direct into child yields no checkpoint (Latest-by-default)`() {
        // A fresh handle simulates first-entry / deep-link — no checkpoint was
        // ever written. The consume returns null (Latest stands).
        val handle = SavedStateHandle()
        assertNull(consumeAnySubAgentCheckpoint(handle, "any-session"))
    }

    @Test
    fun `failed openSubAgent leaves no checkpoint on the handle`() {
        // §B5 BLOCK-fix MAJOR 1: the checkpoint write is INSIDE the
        // openSubAgent success callback — a failed fetch never writes. Simulate
        // by inspecting immediately (no write) and confirming the handle is empty.
        val handle = SavedStateHandle()
        assertNull("no checkpoint written on failed fetch",
            consumeAnySubAgentCheckpoint(handle, "parent"))
    }

    @Test
    fun `checkpointKeyForChild produces the expected prefix`() {
        // The key shape is constant-prefix + childId. Verified so a future
        // refactor cannot accidentally break the consume iteration.
        assertEquals("subAgentCheckpoint:child-1", checkpointKeyForChild("child-1"))
        assertEquals("subAgentCheckpoint:", checkpointKeyForChild(""))
        assertTrue(checkpointKeyForChild("x").startsWith(SUB_AGENT_CHECKPOINT_KEY_PREFIX))
    }
}
