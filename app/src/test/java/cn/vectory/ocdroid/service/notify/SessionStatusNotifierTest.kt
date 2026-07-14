package cn.vectory.ocdroid.service.notify

import cn.vectory.ocdroid.service.lifecycle.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FGS spec §4.1 / §7 — pure-JVM unit tests for [SessionStatusNotifier].
 *
 * Covers every cell of the (Layer, busyCount, degraded) matrix the §4.1
 * table calls out:
 *  - L1-busy + single → 「1 task running」 + ongoing + chronometer + close Action;
 *  - L1-busy + multi → 「N tasks running」 plural;
 *  - L1-idle → 「Connected」 + LOW + NOT ongoing (no FGS slot);
 *  - L2-active → busy copy (same as L1-busy);
 *  - L2-idle → 「Idle monitoring」 + LOW + ongoing + close Action (shell kept);
 *  - L3 → not ongoing (FGS torn down);
 *  - degraded → 「Open app to confirm trust」 + ongoing + close Action;
 *  - placeholder → 「Restoring connection…」 + LOW + ongoing + no Action.
 *
 * Pure JVM (no Robolectric) — [NotificationStrings] is constructed directly.
 */
class SessionStatusNotifierTest {

    private val strings = NotificationStrings(
        appName = "OC Droid",
        restoringConnection = "Restoring connection…",
        busySingular = "1 task running",
        busyPluralFormat = "%1\$d tasks running",
        connected = "Connected",
        idleMonitoring = "Idle monitoring",
        degradedTitle = "Server trust required",
        degradedContent = "Open the app to confirm server trust",
    )

    @Test
    fun `L1 busy singular shows ongoing busy content with chronometer and close action`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L1(busy = true),
            busyCount = 1,
            strings = strings,
            busySinceMs = 1_000L,
            degraded = false,
        )
        assertEquals("1 task running", spec.content)
        assertEquals(NotificationSpec.PRIORITY_LOW, spec.priority)
        assertTrue("L1-busy is ongoing (FGS slot held)", spec.ongoing)
        assertTrue("chronometer shown for busy", spec.showChronometer)
        assertEquals(1_000L, spec.chronometerBaseMs)
        assertTrue("§16-U1 close Action surfaced", spec.showCloseAction)
        assertFalse("not degraded", spec.degraded)
    }

    @Test
    fun `L1 busy plural formats count via busyPluralFormat`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L1(busy = true),
            busyCount = 3,
            strings = strings,
            busySinceMs = 1_000L,
            degraded = false,
        )
        assertEquals("3 tasks running", spec.content)
        assertTrue(spec.ongoing)
    }

    @Test
    fun `L2Active busy mirrors L1-busy content`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L2Active,
            busyCount = 2,
            strings = strings,
            busySinceMs = 5_000L,
            degraded = false,
        )
        assertEquals("2 tasks running", spec.content)
        assertTrue(spec.ongoing)
        assertTrue(spec.showChronometer)
        assertEquals(5_000L, spec.chronometerBaseMs)
    }

    @Test
    fun `L1 idle shows Connected NOT ongoing no chronometer no action (section 4_1)`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L1(busy = false),
            busyCount = 0,
            strings = strings,
            busySinceMs = null,
            degraded = false,
        )
        assertEquals("Connected", spec.content)
        assertEquals(NotificationSpec.PRIORITY_LOW, spec.priority)
        assertFalse("L1-idle = normal Service (no FGS slot)", spec.ongoing)
        assertFalse(spec.showChronometer)
        assertNull(spec.chronometerBaseMs)
        assertFalse("L1-idle has no close Action (no FGS to close)", spec.showCloseAction)
    }

    @Test
    fun `L2Idle shows Idle monitoring ongoing with close action (section 4_1)`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L2Idle,
            busyCount = 0,
            strings = strings,
            busySinceMs = null,
            degraded = false,
        )
        assertEquals("Idle monitoring", spec.content)
        assertEquals(NotificationSpec.PRIORITY_LOW, spec.priority)
        assertTrue("L2Idle FGS shell kept — ongoing", spec.ongoing)
        assertFalse(spec.showChronometer)
        assertTrue("§16-U1 close Action surfaced", spec.showCloseAction)
    }

    @Test
    fun `L3 is not ongoing (FGS torn down)`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L3,
            busyCount = 0,
            strings = strings,
            busySinceMs = null,
            degraded = false,
        )
        assertEquals(NotificationSpec.PRIORITY_LOW, spec.priority)
        assertFalse("L3 = no FGS", spec.ongoing)
        assertFalse(spec.showCloseAction)
        assertFalse(spec.degraded)
    }

    @Test
    fun `degraded overrides layer with Open-app hint and stays ongoing (section 5)`() {
        // Even if the layer says L1-idle (not ongoing), degraded forces ongoing.
        val spec = SessionStatusNotifier.build(
            layer = Layer.L1(busy = false),
            busyCount = 0,
            strings = strings,
            busySinceMs = null,
            degraded = true,
        )
        assertEquals("Server trust required", spec.title)
        assertEquals("Open the app to confirm server trust", spec.content)
        assertTrue("degraded stays ongoing", spec.ongoing)
        assertTrue("§16-U1 close Action still reachable on degraded", spec.showCloseAction)
        assertTrue(spec.degraded)
        assertFalse(spec.showChronometer)
    }

    @Test
    fun `degraded overrides L2Active busy too`() {
        val spec = SessionStatusNotifier.build(
            layer = Layer.L2Active,
            busyCount = 5,
            strings = strings,
            busySinceMs = 1_000L,
            degraded = true,
        )
        assertEquals("Server trust required", spec.title)
        assertTrue(spec.degraded)
        assertFalse(spec.showChronometer)
    }

    @Test
    fun `placeholder is LOW ongoing Restoring with no action`() {
        val spec = SessionStatusNotifier.buildPlaceholder(strings)
        assertEquals("OC Droid", spec.title)
        assertEquals("Restoring connection…", spec.content)
        assertEquals(NotificationSpec.PRIORITY_LOW, spec.priority)
        assertTrue("placeholder ongoing — FGS slot held in 5s ANR window", spec.ongoing)
        assertFalse(spec.showChronometer)
        assertFalse("placeholder has no close Action (transient)", spec.showCloseAction)
        assertFalse(spec.degraded)
    }

    @Test
    fun `busy with null busySinceMs hides chronometer but keeps ongoing`() {
        // §7 chronometer is a nice-to-have; null base = no chronometer, but
        // the busy copy + ongoing + close Action are unaffected.
        val spec = SessionStatusNotifier.build(
            layer = Layer.L2Active,
            busyCount = 1,
            strings = strings,
            busySinceMs = null,
            degraded = false,
        )
        assertEquals("1 task running", spec.content)
        assertTrue(spec.ongoing)
        assertFalse(spec.showChronometer)
        assertNull(spec.chronometerBaseMs)
    }
}
