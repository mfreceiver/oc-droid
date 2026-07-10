package cn.vectory.ocdroid.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.TofuDecision
import cn.vectory.ocdroid.data.repository.http.TofuFailureReason
import cn.vectory.ocdroid.data.repository.http.TofuValidation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * §tofu R2: instrumented test for [TofuTrustDialog]. Renders the dialog from a
 * fake [OpenCodeRepository.TofuCaptureResult] (no live TLS server needed — the
 * dialog is pure UI driven by the captured payload) and asserts:
 *  - cert fields (subject / issuer / expiry / fingerprint) render,
 *  - the 3 decision buttons (Accept once / Trust / Cancel) render,
 *  - tapping each button invokes [onDecision] with the right [TofuDecision]
 *    variant carrying the captured SPKI (or Cancel singleton).
 *
 * The leaf cert is extracted from the existing `mtls_legacy_test.p12` asset
 * (reused from [cn.vectory.ocdroid.MtlsPkcs12CompatInstrumentedTest]) — a real
 * X509Certificate is needed because the dialog reads subjectX500Principal /
 * issuerX500Principal / notAfter. mockk is NOT in androidTestImplementation,
 * so we use the real cert fixture instead.
 *
 * Compose UI test (needs an emulator) — NOT a JVM unit test.
 */
@RunWith(AndroidJUnit4::class)
class TofuTrustDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun readAsset(name: String): ByteArray {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open(name).use { it.readBytes() }
    }

    /** Extracts the leaf X509Certificate from the legacy test p12 fixture. */
    private fun leafFromFixtureP12(): X509Certificate {
        val p12Bytes = readAsset("mtls_legacy_test.p12")
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(ByteArrayInputStream(p12Bytes), CharArray(0))
        val alias = ks.aliases().toList().first { ks.isKeyEntry(it) }
        return ks.getCertificate(alias) as X509Certificate
    }

    /** Builds a fake capture from the fixture cert + a fixed fake SPKI. */
    private fun fakeCapture(
        reason: TofuFailureReason = TofuFailureReason.UNKNOWN_ISSUER
    ): OpenCodeRepository.TofuCaptureResult {
        val leaf = leafFromFixtureP12()
        // Fake SPKI (the dialog test doesn't need the real hash, just a stable
        // value to round-trip through the Accept/Trust decision).
        val spki = "ab".repeat(32)
        return OpenCodeRepository.TofuCaptureResult(
            hostPort = "tofu-ui-test.example:443",
            leaf = leaf,
            spkiHex = spki,
            validation = TofuValidation.Fail(reason, "test reason")
        )
    }

    @Test
    fun renders_all_cert_fields_and_three_decision_buttons() {
        val capture = fakeCapture()
        composeRule.setContent {
            Surface(modifier = Modifier.testTag("tofu.root")) {
                TofuTrustDialog(capture = capture, onDecision = {})
            }
        }

        composeRule.waitForIdle()
        // Field labels.
        composeRule.onNodeWithText("Subject").assertIsDisplayed()
        composeRule.onNodeWithText("Issuer").assertIsDisplayed()
        composeRule.onNodeWithText("Expires").assertIsDisplayed()
        composeRule.onNodeWithText("SHA-256 fingerprint").assertIsDisplayed()
        // Three decision buttons.
        composeRule.onNodeWithText("Accept once").assertIsDisplayed()
        composeRule.onNodeWithText("Trust permanently").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun tapping_accept_once_invokes_AcceptOnce_with_captured_spki() {
        val capture = fakeCapture()
        var fired: TofuDecision? = null
        composeRule.setContent {
            Surface {
                TofuTrustDialog(capture = capture, onDecision = { fired = it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Accept once").performClick()
        composeRule.waitForIdle()
        assertTrue("AcceptOnce fired", fired is TofuDecision.AcceptOnce)
        assertEquals(capture.spkiHex, (fired as TofuDecision.AcceptOnce).spki)
    }

    @Test
    fun tapping_trust_invokes_Trust_with_captured_spki() {
        val capture = fakeCapture()
        var fired: TofuDecision? = null
        composeRule.setContent {
            Surface {
                TofuTrustDialog(capture = capture, onDecision = { fired = it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Trust permanently").performClick()
        composeRule.waitForIdle()
        assertTrue("Trust fired", fired is TofuDecision.Trust)
        assertEquals(capture.spkiHex, (fired as TofuDecision.Trust).spki)
    }

    @Test
    fun tapping_cancel_invokes_Cancel_singleton() {
        val capture = fakeCapture()
        var fired: TofuDecision? = null
        composeRule.setContent {
            Surface {
                TofuTrustDialog(capture = capture, onDecision = { fired = it })
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitForIdle()
        assertEquals("Cancel fired", TofuDecision.Cancel, fired)
    }
}
