package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/** Direct contract tests for the three-state slim incarnation lifecycle. */
class SlimIncarnationStateTest {
    private fun ticket(): OpenCodeRepository.SlimReconfigureTicket =
        OpenCodeRepository.SlimReconfigureTicket(Any())

    @Test
    fun `begin from Ready creates Reconfiguring owned by ticket`() {
        val owner = ticket()

        val result = SlimIncarnationState.begin(SlimIncarnationState.Ready, owner)

        assertEquals(SlimIncarnationState.Reconfiguring(owner), result)
    }

    @Test
    fun `begin from Failed creates Reconfiguring owned by ticket`() {
        val owner = ticket()

        val result = SlimIncarnationState.begin(SlimIncarnationState.Failed, owner)

        assertEquals(SlimIncarnationState.Reconfiguring(owner), result)
    }

    @Test
    fun `complete with owner ticket returns Ready`() {
        val owner = ticket()

        val result = SlimIncarnationState.complete(
            SlimIncarnationState.Reconfiguring(owner),
            owner,
        )

        assertSame(SlimIncarnationState.Ready, result)
    }

    @Test
    fun `complete with a different ticket rejects ownership`() {
        val owner = ticket()

        assertThrows(IllegalArgumentException::class.java) {
            SlimIncarnationState.complete(
                SlimIncarnationState.Reconfiguring(owner),
                ticket(),
            )
        }
    }

    @Test
    fun `markFailed with owner ticket enters Failed`() {
        val owner = ticket()

        val result = SlimIncarnationState.markFailed(
            SlimIncarnationState.Reconfiguring(owner),
            owner,
        )

        assertSame(SlimIncarnationState.Failed, result)
    }

    @Test
    fun `wrong ticket cannot change active Reconfiguring state`() {
        val owner = ticket()
        val state = SlimIncarnationState.Reconfiguring(owner)

        assertSame(state, SlimIncarnationState.markFailed(state, ticket()))
    }

    @Test
    fun `terminal states do not create illegal transitions`() {
        val owner = ticket()

        assertSame(SlimIncarnationState.Ready, SlimIncarnationState.markFailed(SlimIncarnationState.Ready, owner))
        assertSame(SlimIncarnationState.Failed, SlimIncarnationState.markFailed(SlimIncarnationState.Failed, owner))
        assertThrows(IllegalArgumentException::class.java) {
            SlimIncarnationState.complete(SlimIncarnationState.Ready, owner)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SlimIncarnationState.complete(SlimIncarnationState.Failed, owner)
        }
    }
}
