package com.yage.opencode_client

import android.app.Application
import android.content.ComponentCallbacks2
import com.yage.opencode_client.di.AppLifecycleMonitor
import com.yage.opencode_client.ui.util.HttpImageHolder
import com.yage.opencode_client.util.AppLocaleController
import com.yage.opencode_client.util.CrashLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenCodeApp : Application() {
    /**
     * §15.2 / §18: injected so Hilt constructs the [AppLifecycleMonitor]
     * singleton eagerly at Application creation. The monitor wires its own
     * ActivityLifecycleCallbacks in its `init {}`; we just need to touch it
     * here to force instantiation before any Activity starts.
     */
    @Inject
    lateinit var appLifecycleMonitor: AppLifecycleMonitor

    override fun onCreate() {
        super.onCreate()
        // Install the crash logger FIRST so any crash (including early init)
        // is persisted to a retrievable file (see CrashLogger). Must precede
        // any code that could throw.
        CrashLogger.install(this)
        // Apply the app's locale policy synchronously at Application onCreate
        // (before any Activity frame), so non-English devices do not flash the
        // default locale for one frame. See AppLocaleController for the policy.
        AppLocaleController.applySystemLocale()
        // §18.1: create the two notification channels up front (idempotent,
        // wrapped in try/catch inside createChannels). Required before any
        // notify() call, otherwise notifications silently no-op on API 26+.
        AppLifecycleMonitor.createChannels(this)
        // R-06: the former warmUpWebViewAfterLaunch() pre-warmed a throwaway
        // WebView on the main thread to prime the WebView singleton's class
        // loader / JNI init (~50ms on cold start). Removed: that work ran on
        // the main thread during Application onCreate (competing with startup
        // latency budgets) and kept a leaked warmed instance around. The
        // first real WebView (MarkdownWebPreviewPane) now pays the one-time
        // init cost when the user actually opens a markdown web preview,
        // which is an acceptable, lazy trade-off. See MarkdownWebPreviewPane.
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // R-03: release the markdown image in-memory bitmap cache under
        // memory pressure to avoid OOM. The disk cache survives (bounded +
        // self-evicting), so cleared entries re-decode lazily. TRIM_MEMORY_UI_HIDDEN
        // (20) is NOT memory pressure — it just means the UI went to background —
        // so it is deliberately excluded.
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> HttpImageHolder.onLowMemory()
        }
    }
}
