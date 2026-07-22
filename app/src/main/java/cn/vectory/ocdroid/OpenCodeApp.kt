package cn.vectory.ocdroid

import android.app.Application
import android.content.ComponentCallbacks2
import cn.vectory.ocdroid.di.AppLifecycleMonitor
import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.util.HttpImageHolder
import cn.vectory.ocdroid.ui.controller.BackgroundUnreadPoller
import cn.vectory.ocdroid.ui.controller.SessionMetadataPoller
import cn.vectory.ocdroid.util.AppLocaleController
import cn.vectory.ocdroid.util.CrashLogger
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

    @Inject
    lateinit var backgroundUnreadPoller: BackgroundUnreadPoller

    @Inject
    lateinit var sessionMetadataPoller: SessionMetadataPoller

    /** §R-17 batch3: injected so cleanup() can be called on process teardown. */
    @Inject
    lateinit var appCore: AppCore

    override fun onCreate() {
        super.onCreate()
        // Install the crash logger FIRST so any crash (including early init)
        // is persisted to a retrievable file (see CrashLogger). Must precede
        // any code that could throw.
        CrashLogger.install(this)
        // §P5a (Q5): apply the user's persisted language preference (Follow
        // System / 中文 / English) BEFORE the first Activity frame. This is the
        // authoritative cold-start application point — OpenCodeApp.onCreate
        // runs once per process launch, before any Activity is constructed, so
        // the correct locale is in place with no English flash on non-EN
        // devices. MainActivity.onCreate re-applies on warm recreates to catch
        // a SYSTEM-locale change that happened while the process was alive.
        // SettingsManager is available here via the Hilt-injected appCore.
        AppLocaleController.applyPersisted(this, appCore.settingsManager)
        // §18.1/T3b: create notification channels up front (idempotent,
        // wrapped in try/catch inside createChannels). Required before any
        // notify() call, otherwise notifications silently no-op on API 26+.
        AppLifecycleMonitor.createChannels(this)
        appLifecycleMonitor.registerBackgroundUnreadPoller(backgroundUnreadPoller::poll)
        // R-06: the former warmUpWebViewAfterLaunch() pre-warmed a throwaway
        // WebView on the main thread to prime the WebView singleton's class
        // loader / JNI init (~50ms on cold start). Removed: that work ran on
        // the main thread during Application onCreate (competing with startup
        // latency budgets) and kept a leaked warmed instance around. The
        // first real WebView (MarkdownWebPreviewPane) now pays the one-time
        // init cost when the user actually opens a markdown web preview,
        // which is an acceptable, lazy trade-off. See MarkdownWebPreviewPane.
    }

    override fun onTerminate() {
        super.onTerminate()
        // §R-17 batch3: best-effort teardown (framework does not guarantee
        // onTerminate is called; OS process death reclaims everything regardless).
        appCore.cleanup()
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
