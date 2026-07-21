package cn.vectory.ocdroid.data.repository.http

/**
 * O-C weak-network §2: traffic-attribution ledger snapshot.
 * Records cumulative bytes and request count, split by source/category:
 *  - [slimapi]: requests to `/slimapi/...` paths (sessions, questions,
 *     permissions, messages, health, SSE events).
 *  - [tunnel]: all other HTTP traffic flowing through the shared interceptor
 *     chain (REST, mutation, command, SSE — only SSE events path is
 *     `/slimapi/events` which falls under slimapi category).
 *
 * Thread-safe: values are atomic snapshots from the live counters at the
 * instant of [TrafficCountingInterceptor.snapshot].
 */
data class TrafficLedgerSnapshot(
    val slimapiBytes: Long = 0L,
    val slimapiRequests: Long = 0L,
    val tunnelBytes: Long = 0L,
    val tunnelRequests: Long = 0L,
) {
    /** Total bytes across all categories, for backward-compat summary. */
    val totalBytes: Long get() = slimapiBytes + tunnelBytes
    val totalRequests: Long get() = slimapiRequests + tunnelRequests
}
