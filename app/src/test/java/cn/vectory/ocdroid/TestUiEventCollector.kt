package cn.vectory.ocdroid

import cn.vectory.ocdroid.ui.AppCore
import cn.vectory.ocdroid.ui.UiEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * §R18 Phase 4 (P2-3): test-only UiEvent ring buffer. Extracted out of the
 * former `TestAppStateShim.kt` (deleted in the same phase) so that the
 * `recentTestErrors` / `recentTestSuccesses` helpers survive the shim removal
 * — the 12 legacy `state.value.error` / `state.value.successMessage` read
 * sites now read these directly instead of going through an aggregated
 * `AppState`.
 *
 * Production NEVER sees these buffers (test-only `internal` extensions on
 * [AppCore]; no production code reads them).
 *
 * §R18 Phase 2-G: the buffers store the **resolved English text** of each
 * UiEvent (via [testStringFor]) so historical String assertions keep their
 * semantic. Tests that need the raw event (e.g. to assert on `resId` / `args`
 * directly) use [lastErrorEvent] / [lastSuccessEvent].
 */

// ── UiEvent ring buffers (test-only) ───────────────────────────────────────
//
// §R-17 batch3e: SharedFlow has no replay, so once an Error/Success event has
// been emitted it's gone. We side-channel the events into per-AppCore ring
// buffers so test assertions can read the most-recent Error/Success without
// rewriting to Turbine.

private val appCoreErrorBuffer = mutableMapOf<AppCore, MutableList<String>>()
private val appCoreSuccessBuffer = mutableMapOf<AppCore, MutableList<String>>()
private val appCoreErrorEventBuffer = mutableMapOf<AppCore, MutableList<UiEvent.Error>>()
private val appCoreSuccessEventBuffer = mutableMapOf<AppCore, MutableList<UiEvent.Success>>()
// §compact-graded (Blocker-1): Info ring buffer added so the new compact
// grading tests can assert on which Info resId was emitted (the read-timeout
// branch fires Info, the watchdog fires a different Info). Previously Info
// was a no-op in the collector, which made it impossible to distinguish the
// two branches by their emitted event.
private val appCoreInfoEventBuffer = mutableMapOf<AppCore, MutableList<UiEvent.Info>>()
private val appCoreCollectorJobs = mutableMapOf<AppCore, Job>()

/** Most-recent-last list of Error event messages emitted on [AppCore.uiEvents]
 *  (resolved to English text via [testStringFor]). */
internal val AppCore.recentTestErrors: List<String>
    get() = synchronized(appCoreErrorBuffer) { appCoreErrorBuffer[this]?.toList() ?: emptyList() }

/** Most-recent-last list of Success event messages emitted on [AppCore.uiEvents]
 *  (resolved to English text via [testStringFor]). */
internal val AppCore.recentTestSuccesses: List<String>
    get() = synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer[this]?.toList() ?: emptyList() }

/** Most-recent-last list of raw [UiEvent.Error] events (test introspection). */
internal val AppCore.recentTestErrorEvents: List<UiEvent.Error>
    get() = synchronized(appCoreErrorEventBuffer) { appCoreErrorEventBuffer[this]?.toList() ?: emptyList() }

/** Most-recent-last list of raw [UiEvent.Success] events (test introspection). */
internal val AppCore.recentTestSuccessEvents: List<UiEvent.Success>
    get() = synchronized(appCoreSuccessEventBuffer) { appCoreSuccessEventBuffer[this]?.toList() ?: emptyList() }

/**
 * §compact-graded (Blocker-1): most-recent-last list of raw [UiEvent.Info]
 * events. Used by the compact grading tests to distinguish the read-timeout
 * branch (`info_compact_in_progress`) from the watchdog branch
 * (`info_compact_timeout_retry`).
 */
internal val AppCore.recentTestInfoEvents: List<UiEvent.Info>
    get() = synchronized(appCoreInfoEventBuffer) { appCoreInfoEventBuffer[this]?.toList() ?: emptyList() }

/** Convenience: the most recent raw Error event, or null. */
internal val AppCore.lastErrorEvent: UiEvent.Error?
    get() = recentTestErrorEvents.lastOrNull()

/** Convenience: the most recent raw Success event, or null. */
internal val AppCore.lastSuccessEvent: UiEvent.Success?
    get() = recentTestSuccessEvents.lastOrNull()

/** §compact-graded (Blocker-1): the most recent raw Info event, or null. */
internal val AppCore.lastInfoEvent: UiEvent.Info?
    get() = recentTestInfoEvents.lastOrNull()

/**
 * Install a coroutine collector on [AppCore.uiEvents] that records every Error /
 * Success event into the per-AppCore ring buffers. Idempotent — multiple
 * calls on the same AppCore share one collector. The collector is launched
 * on [scope] (typically the runTest scope's Main dispatcher).
 *
 * Tests do NOT call this directly — [MainViewModelTestBase.createCore]
 * wires it up automatically when each test constructs an [AppCore].
 */
internal fun AppCore.installTestUiEventCollector(
    scope: CoroutineScope,
) {
    synchronized(appCoreCollectorJobs) {
        if (appCoreCollectorJobs.containsKey(this)) return
    }
    synchronized(appCoreErrorBuffer) { appCoreErrorBuffer.getOrPut(this) { mutableListOf() } }
    synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer.getOrPut(this) { mutableListOf() } }
    synchronized(appCoreErrorEventBuffer) { appCoreErrorEventBuffer.getOrPut(this) { mutableListOf() } }
    synchronized(appCoreSuccessEventBuffer) { appCoreSuccessEventBuffer.getOrPut(this) { mutableListOf() } }
    synchronized(appCoreInfoEventBuffer) { appCoreInfoEventBuffer.getOrPut(this) { mutableListOf() } }
    val job = scope.launch {
        uiEvents.collect { event ->
            when (event) {
                is UiEvent.Error -> {
                    val resolved = testStringFor(event.resId, event.args)
                    synchronized(appCoreErrorBuffer) {
                        appCoreErrorBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(resolved)
                    }
                    synchronized(appCoreErrorEventBuffer) {
                        appCoreErrorEventBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(event)
                    }
                }
                is UiEvent.Success -> {
                    val resolved = testStringFor(event.resId, event.args)
                    synchronized(appCoreSuccessBuffer) {
                        appCoreSuccessBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(resolved)
                    }
                    synchronized(appCoreSuccessEventBuffer) {
                        appCoreSuccessEventBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(event)
                    }
                }
                // §compact-graded (Blocker-1): Info events now buffered (was a
                // no-op) so the compact grading tests can assert which Info
                // was emitted (read-timeout vs watchdog). The historical ring
                // buffers (recentTestErrors / recentTestSuccesses) remain
                // Error/Success-only, so existing String assertions are
                // unaffected.
                is UiEvent.Info -> {
                    synchronized(appCoreInfoEventBuffer) {
                        appCoreInfoEventBuffer.getOrPut(this@installTestUiEventCollector) { mutableListOf() }.add(event)
                    }
                }
                is UiEvent.Debug -> Unit
            }
        }
    }
    synchronized(appCoreCollectorJobs) { appCoreCollectorJobs[this] = job }
}

/** Drop the per-AppCore collector state. Called from @After cleanup. */
internal fun AppCore.uninstallTestUiEventCollector() {
    synchronized(appCoreCollectorJobs) {
        appCoreCollectorJobs.remove(this)?.cancel()
    }
    synchronized(appCoreErrorBuffer) { appCoreErrorBuffer.remove(this) }
    synchronized(appCoreSuccessBuffer) { appCoreSuccessBuffer.remove(this) }
    synchronized(appCoreErrorEventBuffer) { appCoreErrorEventBuffer.remove(this) }
    synchronized(appCoreSuccessEventBuffer) { appCoreSuccessEventBuffer.remove(this) }
    synchronized(appCoreInfoEventBuffer) { appCoreInfoEventBuffer.remove(this) }
}

/**
 * §R18 Phase 2-G: test-only string resolver. Mirrors the default-locale
 * (English) values from `app/src/main/res/values/strings.xml` so historical
 * String assertions resolve to the same text the production emitter would
 * render in an English locale.
 *
 * Tests that need locale-sensitive assertions should use [lastErrorEvent] /
 * [lastSuccessEvent] and assert on `resId` + `args` directly.
 *
 * **Maintenance note**: when adding a new UiEvent string, add its English
 * format template here too. The two columns MUST stay in sync.
 */
private fun testStringFor(resId: Int, args: List<Any>): String {
    val template = TEST_UI_EVENT_STRING_TABLE[resId]
        ?: return "<unmapped UiEvent resId=$resId args=$args>"
    return if (args.isEmpty()) template else String.format(java.util.Locale.US, template, *args.toTypedArray())
}

private val TEST_UI_EVENT_STRING_TABLE: Map<Int, String> = mapOf(
    R.string.error_load_messages_failed to "Failed to load messages: %1\$s",
    R.string.error_load_sessions_failed to "Failed to load sessions: %1\$s",
    R.string.error_load_more_sessions_failed to "Failed to load more sessions: %1\$s",
    R.string.error_command_failed to "Command /%1\$s failed: %2\$s",
    R.string.command_submitted_processing to "Command submitted, processing…",
    R.string.chat_command_no_session to "Open or create a session before running /%1\$s",
    R.string.error_create_session_in_workdir_failed to "Failed to create session in %1\$s: %2\$s",
    R.string.error_restore_session_failed to "Failed to restore session: %1\$s",
    R.string.error_compact_no_session to "Open or create a session before compacting",
    R.string.error_compact_no_model to "No model info available for compaction",
    R.string.error_compact_failed to "Compact failed: %1\$s",
    // §compact-graded (Blocker-1): neutral Info emitted by compactSession on
    // the accepted / read-timeout / watchdog branches. Mapped here so any
    // future Info string assertion stays locale-stable.
    R.string.info_compact_in_progress to "Compaction in progress, the result will sync automatically.",
    R.string.info_compact_timeout_retry to "Compaction timed out with no response; please retry.",
    R.string.error_edit_message_failed to "Failed to edit message: %1\$s",
    R.string.error_abort_session_failed to "Failed to abort session: %1\$s",
    R.string.success_refreshed to "Refreshed",
    R.string.error_session_sse_named to "%1\$s: %2\$s",
    R.string.error_session_sse_unnamed to "%1\$s",
    R.string.error_create_session_failed to "Failed to create session: %1\$s",
    R.string.error_fork_session_failed to "Failed to fork session: %1\$s",
    R.string.error_archive_session_failed to "Failed to archive session: %1\$s",
    R.string.error_delete_session_failed to "Failed to delete session: %1\$s",
    R.string.error_send_message_failed to "Failed to send message: %1\$s",
    R.string.error_child_session_unavailable to "Sub-agent session unavailable",
    R.string.error_tunnel_password_unset to "Tunnel activation failed: tunnel auth password not set. Please fill in the tunnel password in Server settings and save before retrying.",
    R.string.error_tunnel_password_empty to "Tunnel activation failed: password id is configured but the stored value is empty (possibly not entered when saving). Please re-enter the tunnel password and save.",
    R.string.success_tunnel_activated to "Tunnel activated successfully",
    R.string.error_tunnel_activation_failed to "Tunnel activation failed: %1\$s",
    R.string.error_connection_failed to "Connection failed: %1\$s",
    R.string.error_sse_failed to "SSE Error: %1\$s",
    R.string.error_respond_permission_failed to "Failed to respond to permission: %1\$s",
)
