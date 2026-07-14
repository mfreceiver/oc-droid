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
    override fun terminate() {
        val intent = Intent(context, SessionStreamingService::class.java).apply {
            action = SessionStreamingService.ACTION_CLOSE_BACKGROUND
        }
        context.startService(intent)
    }
}
