@file:Suppress("unused")

package cn.vectory.ocdroid.data.repository

// ════════════════════════════════════════════════════════════════════════════
// lite-v2-dev (plan §4.1): RETIRED.
//
// This file previously hosted the stage-A slim authoritative-commit machinery:
//   - `data class SlimAuthoritativeCandidate`
//   - `interface SlimAuthoritativeCommitter`
//   - `internal class InternalSlimAuthoritativeCommitter`
//
// All three depended on `SlimSessionState` (defined in the now-deleted
// `SlimSseReducer.kt`) and the token-guarded commit hooks on the now-deleted
// `SlimSseStateMachine`. With the lite-v2 simplification (slim digest/done/
// resync/part.removed routes through `SkeletonReloadCoordinator`), there are
// no live main-source callers — only KDoc `[...]` references remain (which are
// non-binding) and the dedicated unit-test `SlimAuthoritativeCommitTest.kt`
// (which tested the deleted machinery and is itself now obsolete).
//
// The file is kept (not deleted) as a tombstone so reviewers can see what was
// here and why it vanished; git history retains the full implementation.
// ════════════════════════════════════════════════════════════════════════════
