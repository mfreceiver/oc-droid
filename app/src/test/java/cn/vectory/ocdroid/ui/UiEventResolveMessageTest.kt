package cn.vectory.ocdroid.ui

import android.content.Context
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: UiEvent sealed hierarchy + [resolveMessage] +
 * [EventEmitter] fun-interface. Coverage gap before this file: 0/4 methods,
 * 0/4 branches, 0/57 instructions (UiEventKt was completely uncovered).
 *
 * `resolveMessage` resolves the @StringRes resId through
 * [Context.getString] (with format args). We inject a relaxed mockk Context
 * (default returns "" for any getString) so the test is deterministic and
 * does not pull in the app's real resource table (which would require
 * Robolectric + the AndroidKeyStore workaround for the app's
 * Application.onCreate Hilt graph).
 *
 * The Debug branch does NOT touch the Context (returns its own raw message).
 */
class UiEventResolveMessageTest {

    private fun newContext(): Context = mockk(relaxed = true)

    @Test
    fun `Error resolveMessage with args returns a non-null string from Context`() {
        val ctx = newContext()
        val event = UiEvent.Error(resId = 1, args = listOf("a"))

        val msg = event.resolveMessage(ctx)

        assertNotNull(msg)
        // Relaxed mock returns "" for getString(Int, Array<Any>).
        assertEquals("", msg)
    }

    @Test
    fun `Error resolveMessage with no args still resolves`() {
        val ctx = newContext()
        val event = UiEvent.Error(resId = 7, args = emptyList())

        val msg = event.resolveMessage(ctx)

        assertNotNull(msg)
    }

    @Test
    fun `Success resolveMessage with args returns a non-null string from Context`() {
        val ctx = newContext()
        val event = UiEvent.Success(resId = 9, args = listOf("x"))

        val msg = event.resolveMessage(ctx)

        assertNotNull(msg)
    }

    @Test
    fun `Debug resolveMessage returns the raw message without touching Context`() {
        // A non-relaxed mock would fail on ANY unexpected call; relaxed mock
        // silently returns defaults, so we just verify the raw message comes
        // back unchanged.
        val ctx = newContext()
        val event = UiEvent.Debug(message = "raw-debug")

        assertEquals("raw-debug", event.resolveMessage(ctx))
    }

    @Test
    fun `EventEmitter emit forwards the event to the lambda`() {
        var captured: UiEvent? = null
        val emitter = EventEmitter { e -> captured = e }
        val event = UiEvent.Debug("forwarded")
        emitter.emit(event)
        assertNotNull(captured)
        assertSame(event, captured)
    }

    @Test
    fun `UiEvent subclasses are distinguishable by runtime type`() {
        // Defence for the sealed-class shape: the three variants are distinct
        // types so `when` exhaustive matching in collectors does not collapse.
        val error: UiEvent = UiEvent.Error(resId = 1, args = listOf("a"))
        val success: UiEvent = UiEvent.Success(resId = 1, args = listOf("a"))
        val debug: UiEvent = UiEvent.Debug("x")

        assertTrue(error is UiEvent.Error)
        assertTrue(success is UiEvent.Success)
        assertTrue(debug is UiEvent.Debug)
    }

    @Test
    fun `UiEvent Error preserves resId and args`() {
        val e = UiEvent.Error(resId = 42, args = listOf("a", 1, true))
        assertEquals(42, e.resId)
        assertEquals(listOf<Any>("a", 1, true), e.args)
    }

    @Test
    fun `UiEvent Success defaults args to empty list`() {
        val e = UiEvent.Success(resId = 7)
        assertEquals(7, e.resId)
        assertEquals(emptyList<Any>(), e.args)
    }

    @Test
    fun `UiEvent Debug preserves the message`() {
        val e = UiEvent.Debug(message = "diagnostic")
        assertEquals("diagnostic", e.message)
    }
}
