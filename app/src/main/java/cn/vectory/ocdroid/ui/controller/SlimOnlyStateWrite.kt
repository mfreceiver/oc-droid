package cn.vectory.ocdroid.ui.controller

/**
 * T1d P1: fail-fast when a slim-only state-write path is invoked while the
 * coordinator is in legacy mode. Subclass of [IllegalStateException] so
 * existing `assertThrows(IllegalStateException)` (P0-5) still matches.
 */
class SlimOnlyStateWriteException(message: String) : IllegalStateException(message)

/**
 * Pure helper: throw [SlimOnlyStateWriteException] when [isSlim] is false.
 * Message format is frozen by T1d tests (label in brackets + "legacy mode").
 */
internal fun requireSlimOnlyStateWrite(isSlim: Boolean, label: String) {
    if (!isSlim) {
        throw SlimOnlyStateWriteException(
            "slim-only state write [$label] invoked in legacy mode — leak risk",
        )
    }
}
