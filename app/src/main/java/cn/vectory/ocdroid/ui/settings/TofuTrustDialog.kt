package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.R
import cn.vectory.ocdroid.data.repository.OpenCodeRepository
import cn.vectory.ocdroid.data.repository.http.TofuFailureReason
import cn.vectory.ocdroid.data.repository.http.TofuValidation

/**
 * §tofu R2: SSH-style trust-on-first-use dialog. Surfaces a captured server
 * leaf cert (subject / issuer / expiry / SPKI SHA-256 fingerprint) plus a
 * best-effort reason the system trust store rejected it (UNKNOWN_ISSUER /
 * EXPIRED / HOSTNAME_MISMATCH), and lets the user choose:
 *
 *  - **Accept once** ([cn.vectory.ocdroid.data.repository.http.TofuDecision.AcceptOnce]):
 *    session-only pin — re-prompts after process death.
 *  - **Trust permanently** ([cn.vectory.ocdroid.data.repository.http.TofuDecision.Trust]):
 *    persistent pin — survives cold start.
 *  - **Cancel** ([cn.vectory.ocdroid.data.repository.http.TofuDecision.Cancel]):
 *    no pin written; the in-flight connect is settled false.
 *
 * Security note: the dialog itself enforces NOTHING — the actual security gate
 * is the SPKI pin written by [OpenCodeRepository.applyTofuDecision]; the dialog
 * merely decides whether to write it. The reason text (UI tone) can never
 * weaken security because acceptance always goes through the pin.
 *
 * The dialog is rendered from a [capture] payload (no live TLS server needed)
 * so it is fully testable in instrumented tests via a fake TofuCaptureResult.
 *
 * @param capture the captured leaf cert + classification (from
 *  [OpenCodeRepository.captureServerCert]).
 * @param onDecision invoked with the user's choice; the caller forwards it to
 *  [cn.vectory.ocdroid.ui.ConnectionViewModel.resolveTofuTrust].
 */
@Composable
fun TofuTrustDialog(
    capture: OpenCodeRepository.TofuCaptureResult,
    onDecision: (cn.vectory.ocdroid.data.repository.http.TofuDecision) -> Unit,
) {
    val leaf = capture.leaf
    val subject = runCatching { leaf.subjectX500Principal.name }.getOrDefault("(unknown)")
    val issuer = runCatching { leaf.issuerX500Principal.name }.getOrDefault("(unknown)")
    // notAfter is a Date; format defensively (test certs minted with epoch
    // values can produce surprising zones — keep it ISO-stable).
    val expiry = runCatching {
        val d = leaf.notAfter
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(d)
    }.getOrDefault("(unknown)")
    val fingerprint = capture.spkiHex
    val reasonText = when (val v = capture.validation) {
        is TofuValidation.Pass -> null
        is TofuValidation.Fail -> when (v.reason) {
            TofuFailureReason.UNKNOWN_ISSUER -> stringResource(R.string.tofu_cert_reason_unknown_issuer)
            TofuFailureReason.EXPIRED -> stringResource(R.string.tofu_cert_reason_expired)
            TofuFailureReason.HOSTNAME_MISMATCH -> stringResource(R.string.tofu_cert_reason_hostname)
            TofuFailureReason.OTHER -> v.detail
        }
    }
    AlertDialog(
        onDismissRequest = { onDecision(cn.vectory.ocdroid.data.repository.http.TofuDecision.Cancel) },
        title = { Text(stringResource(R.string.tofu_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                CertField(stringResource(R.string.tofu_cert_subject), subject)
                CertField(stringResource(R.string.tofu_cert_issuer), issuer)
                CertField(stringResource(R.string.tofu_cert_expiry), expiry)
                // SPKI fingerprint — the value the user MUST verify out-of-band
                // (call the admin / check the console) before trusting. Split
                // into 8-hex groups so it is scannable like an SSH fingerprint.
                CertField(
                    stringResource(R.string.tofu_cert_fingerprint),
                    fingerprint.chunked(8).joinToString(" "),
                    monospace = true,
                )
                if (reasonText != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        reasonText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("tofu.reason"),
                    )
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = {
                        onDecision(
                            cn.vectory.ocdroid.data.repository.http.TofuDecision
                                .AcceptOnce(capture.spkiHex)
                        )
                    },
                    modifier = Modifier.testTag("tofu.accept_once")
                ) { Text(stringResource(R.string.tofu_accept_once)) }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = {
                        onDecision(
                            cn.vectory.ocdroid.data.repository.http.TofuDecision
                                .Trust(capture.spkiHex)
                        )
                    },
                    modifier = Modifier.testTag("tofu.trust")
                ) { Text(stringResource(R.string.tofu_trust)) }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onDecision(cn.vectory.ocdroid.data.repository.http.TofuDecision.Cancel) },
                modifier = Modifier.testTag("tofu.cancel")
            ) { Text(stringResource(R.string.tofu_cancel)) }
        }
    )
}

@Composable
private fun CertField(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (monospace) MaterialTheme.typography.bodySmall
                .copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("tofu.field.$label")
        )
    }
}
