package cn.vectory.ocdroid.data.repository

/** Pure lifecycle model for the currently published slim incarnation. */
sealed interface SlimIncarnationState {
    data object Ready : SlimIncarnationState
    data class Reconfiguring(
        val ownerTicket: OpenCodeRepository.SlimReconfigureTicket,
    ) : SlimIncarnationState
    data class LocalWipe(
        val ownerTicket: OpenCodeRepository.SlimReconfigureTicket,
        val priorState: SlimIncarnationState,
    ) : SlimIncarnationState
    data object Failed : SlimIncarnationState

    companion object {
        fun begin(
            state: SlimIncarnationState,
            ticket: OpenCodeRepository.SlimReconfigureTicket,
        ): SlimIncarnationState = when (state) {
            Ready, Failed -> Reconfiguring(ticket)
            // A direct reconfigure boundary deliberately supersedes an older
            // owner. Bootstrap never calls begin in this state; it returns
            // ReconfigureInProgress instead.
            is Reconfiguring, is LocalWipe -> Reconfiguring(ticket)
        }

        fun complete(
            state: SlimIncarnationState,
            ticket: OpenCodeRepository.SlimReconfigureTicket,
        ): SlimIncarnationState {
            require(state is Reconfiguring && state.ownerTicket === ticket) {
                "Slim reconfigure ticket does not own the active incarnation"
            }
            return Ready
        }

        fun markFailed(
            state: SlimIncarnationState,
            ticket: OpenCodeRepository.SlimReconfigureTicket,
        ): SlimIncarnationState = when (state) {
            is Reconfiguring -> if (state.ownerTicket === ticket) Failed else state
            is LocalWipe -> state
            Ready, Failed -> state
        }

        fun beginLocalWipe(
            state: SlimIncarnationState,
            ticket: OpenCodeRepository.SlimReconfigureTicket,
        ): SlimIncarnationState = LocalWipe(ticket, state)

        fun completeLocalWipe(
            state: SlimIncarnationState,
            ticket: OpenCodeRepository.SlimReconfigureTicket,
        ): SlimIncarnationState {
            require(state is LocalWipe && state.ownerTicket === ticket) {
                "Slim local wipe ticket does not own the operation"
            }
            return when (val prior = state.priorState) {
                is LocalWipe -> prior
                else -> prior
            }
        }
    }
}
