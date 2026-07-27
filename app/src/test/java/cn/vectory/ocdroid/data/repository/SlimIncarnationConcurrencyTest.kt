package cn.vectory.ocdroid.data.repository

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** B1/B2 ownership contracts; these intentionally exercise atomic claims. */
class SlimIncarnationConcurrencyTest {
    private fun ticket() = OpenCodeRepository.SlimReconfigureTicket(Any())

    @Test
    fun `direct claim does not borrow an active owner's ticket`() {
        val owner = ticket()
        val machine = SlimSseStateMachine(Any())
        machine.beginSlimReconfigure()

        assertTrue(machine.claimDirectConfigure() is SlimSseStateMachine.DirectConfigureClaim.InProgress)
        assertFalse(machine.claimDirectConfigure() is SlimSseStateMachine.DirectConfigureClaim.Owned)
    }

    @Test
    fun `local wipe preserves failed state`() {
        val machine = SlimSseStateMachine(Any())
        val owner = machine.beginSlimReconfigure()
        machine.markSlimReconfigureFailed(owner)

        machine.resetSlimForLocalWipe()

        assertTrue(machine.isFailed())
    }
}
