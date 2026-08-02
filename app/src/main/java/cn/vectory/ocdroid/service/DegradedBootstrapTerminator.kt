package cn.vectory.ocdroid.service

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Sends the existing no-identity close command to a degraded placeholder Service. */
interface DegradedBootstrapTerminator {
    fun terminate()
}

@Singleton
class AndroidDegradedBootstrapTerminator @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DegradedBootstrapTerminator {
    // SessionStreamingService deleted in L1 Commit 2 — terminated path is
    // a no-op. The interface is kept for the probe flow pending further
    // refactoring.
    override fun terminate() {
        // No-op: the FGS was deleted in L1.
    }
}
