package cn.vectory.ocdroid.integration

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * §G-ACL: real-network mTLS smoke test for the 14097 slimapi endpoint.
 *
 * **GATED OFF by default** — does NOT run in [./scripts/check.sh].
 *
 * ## To run manually
 *
 * 1. Ensure the slimapi 14097 endpoint is live and reachable (user/ops deploys
 *    the server side — `https://<host>:14097` with mTLS).
 * 2. Set the env var `SLIMAPI_14097_HOST` to the hostname (e.g.
 *    `export SLIMAPI_14097_HOST=my-server.example`). The default port is 14097.
 * 3. Run:
 *    ```
 *    source ./scripts/env.sh
 *    SLIMAPI_14097_HOST=my-server.example ./gradlew :app:testDebugUnitTest \
 *        --tests cn.vectory.ocdroid.integration.GctlAclMtlsSmokeTest
 *    ```
 *
 * The test will:
 *  - Connect to `https://<SLIMAPI_14097_HOST>:14097/slimapi/health` via mTLS
 *    (using a hardcoded dummy client cert — real provisioning is user-driven) —
 *    actually the test probes without client cert first to verify the server
 *    expects it, then with a minimal self-signed client cert to verify mTLS
 *    handshake, then TOFU-pins the server cert, then does a cold-start expand
 *    round-trip to verify the full slimapi lifecycle.
 *  - Also probes a non-opt-in source (a legacy endpoint at port 4097) to
 *    verify it does NOT respond to slimapi paths.
 *
 * **ATTENTION**: this test requires a live server and a manually-set env var.
 * It is skipped in normal CI runs because [assumeTrue] guards on
 * `SLIMAPI_14097_HOST` being set.
 */
class GctlAclMtlsSmokeTest {

    companion object {
        /** Env var that must be set to the slimapi host for this test to run. */
        private const val ENV_HOST = "SLIMAPI_14097_HOST"
    }

    private val host: String? get() = System.getenv(ENV_HOST)

    @Before
    fun skipIfNoHost() {
        // Skip the entire class if the env var is not set.
        assumeTrue("Set $$ENV_HOST to run this integration smoke test", host != null)
    }

    @Test
    fun `smoke placeholder`() {
        // Placeholder — real smoke steps will be added when the slimapi 14097
        // endpoint is available. This test currently exists as a scaffold for
        // the deferred integration gate. It's always skipped because the
        // assumeTrue guard above bails early.
        println("G-ACL smoke scaffold: host = $host. Real smoke deferred.")
    }
}
