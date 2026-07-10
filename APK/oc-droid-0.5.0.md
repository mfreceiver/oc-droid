Release v0.5.0

### Features

- **persistent chat cache Phase 0** — SQLCipher 4.16.0 encrypted Room foundation (compound PK `[serverGroupFp, sessionId]`) + 32B SecureRandom key in EncryptedSharedPreferences (never rotates) + eager-open with in-memory fallback (plan §0 G4 不崩) + HostProfile.serverGroupFp front-migration + backup exclusion
- **persistent chat cache Phase 1** — group-aware cache + verifyAndLoad single @Transaction (TOCTOU-eliminated fingerprint) + 3 effects (VerifyAndHydrate / EvictSession / EvictGroup) + switchTo async refactor (Step3 emit VerifyAndHydrate, Step7 drop LoadMessages, non-suspend preserved) + 6 onCacheWindow hooks (captured-fp closure) + selectHostProfile 4-step (same/cross-group field classification) + SessionMutationActions/SessionSyncCoordinator emit + loadSessions verify + applySavedSettings hoist + async handler二次复合键 guard + launchLoadMessages captured-fp guard
- **persistent chat cache Phase 2** — non-contiguous message model (slice + gap marker, ≥1 gap) + BackfillAlgorithm (detectGap/stepCoversAnchor/shouldProbe G6) + GapFillCoordinator (50-step state machine, session-level ConcurrentHashMap Mutex) + GapMarkerEntity (CacheDatabase v2) + CacheRepository gap methods (appendOlderSlice single-tx resolve/overlap) + GapInfo abolished + launchCatchUp probe=5 + launchCloseGap removed + Verified hydrate injects gapMarkers + async guard live-provider fp + suspend recheck + switch-away reset Idle
- **persistent chat cache Phase 3** — triple eviction (LRU-50 / 30d / 7d per-serverGroupFp, single-tx cascade) + CacheMaintenanceCoordinator (alive complete enumeration per-workdir+global + 24h dedup + mark-only degradation) + ConnectionCoordinator testConnection healthy hook + SettingsManager lastSweepEpoch
- **persistent chat cache Phase 4+5** — CacheManagementSection (@EntryPoint + group listing + suspect/exhausted/degraded warning) + 6 actions (clearSession/clearProject/sweepNow offline/clearAll confirm/copy-on-split) + SettingsManager 3-category fp-keyed migration (recentWorkdirs/disabled_models/draft NUL composite) + applySavedSettings migration trigger (idempotent) + SessionListActions cross-group merge 3-conditions (mergeServerGroup + mergeCacheGroup PK-conflict-safe) + purgePerHostState field classification + copyPerFpConfig (copy-on-split 5-category) + deleteHostProfile reference counting + messageCount non-empty condition + launchLoadSessions live-provider fp guard

### Gate

- check.sh --full green (2043+ tests + lint + kover ≥60/56)
- Phase 0: glmer 8.0 COND-PASS (blocker fixed) + dser 8.2 COND-PASS
- Phase 1: gpter+glmer+dser consensus, 8 blockers fixed across rounds
- Phase 2: gpter COND-PASS 8 + dser 7.8 + glmer 7.5
- Phase 3: glmer PASS 8.5 + dser COND-PASS 8.5
- Phase 4+5: dser 9.0 PASS + maxer 8.4 COND-PASS + gpter/opuser re-review (opuser PASS 9.0)
