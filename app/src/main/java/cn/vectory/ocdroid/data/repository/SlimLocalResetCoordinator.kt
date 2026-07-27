package cn.vectory.ocdroid.data.repository

import javax.inject.Inject
import javax.inject.Singleton

/** Coordinates the distinct same-host local-wipe operation. */
@Singleton
class SlimLocalResetCoordinator @Inject constructor(
    private val repository: OpenCodeRepository,
) {
    /**
     * Invalidates local slim data without invalidating the live transport.
     * This intentionally does not call completeSlimReconfigure(): no
     * configure transaction is being completed here.
     */
    suspend fun resetSlimForLocalWipe() {
        repository.resetSlimForLocalWipe()
    }
}
