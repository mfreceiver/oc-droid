package cn.vectory.ocdroid.ui.settings

import cn.vectory.ocdroid.data.model.BasicAuthConfig
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.util.certSubjectOrNull
import cn.vectory.ocdroid.util.decodeBase64OrNull
import cn.vectory.ocdroid.util.loadClientP12OrNull
import cn.vectory.ocdroid.util.parseCaCertOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure top-level helpers for the mTLS editor dialog (in
 * [HostProfilesManagerScreen] / [HostProfileEditorDialog]). These were hoisted
 * out of the Compose `onClick` / `withContext` / `produceState` bodies so the
 * decision logic (section-off credential clearing, `name.ifBlank{"Untitled"}`
 * fallback, `effectivePasswordEdited` forced-clear, `hasMaterial` gating,
 * PKCS12 KDF/CA parse decode, post-import state-write mapping, dialog-level
 * `hasCertError` predicate) is unit-testable without spinning up Compose.
 *
 * Lives in a separate (NOT @Composable) file so the class
 * `cn.vectory.ocdroid.ui.settings.MtlsDialogCallBuildersKt` counts toward
 * kover coverage. The host file `HostProfilesManagerScreenKt` is in
 * `kover.filters.excludes.classes` (Compose-heavy), so any pure helper
 * declared there is invisible to coverage — moving them here makes the 49
 * JVM tests in [MtlsDialogCallBuildersTest] actually feed the coverage floor.
 *
 * Same package as [HostProfilesManagerScreen] / [MtlsDialogCallBuildersTest],
 * so the `internal` symbols are visible to both the dialog (call site) and
 * the unit tests (no import needed). `CaStage` lives in
 * [HostProfilesManagerScreen] (used by the dialog) and is referenced by the
 * data classes here via same-package access.
 */

/**
 * §review-r4 (gpter R4 #2): pure — does this edit have usable mTLS client-cert
 * material?
 *
 * `(!clientCleared && clientCertId != null) || stagedP12 != null`. The
 * [clientCleared] signal is what distinguishes "UI slot empty but ESP still
 * holds the old p12" (an existing cert the user just removed → must NOT
 * count) from "existing cert untouched" (counts). Without it, the
 * "remove → re-enable mTLS → don't paste → save" sequence silently reloads
 * the old p12 from ESP.
 *
 * Extracted from the inline expression that was duplicated in
 * `triggerTestConnection` and the Save `onClick` so the rule is unit-testable
 * without Compose (see [MtlsHasMaterialTest]) and reusable for the dialog-level
 * Save-disable (gpter R4 #1).
 */
internal fun mtlsHasMaterial(clientCleared: Boolean, clientCertId: String?, stagedP12: ByteArray?): Boolean =
    (!clientCleared && clientCertId != null) || stagedP12 != null

/**
 * §review-r4 (gpter R4 #3): positional-arg bundle produced by [buildSaveCall]
 * and consumed by the dialog's `onSave` 11-arg callback. Field order matches
 * the onSave positional call site verbatim — the dialog splats the result
 * without re-ordering. Kept as a separate top-level type so the field accessors
 * count as covered JVM lines independent of the (Kotlin-synthetic) Kt class
 * hosting the builder function.
 */
data class SaveCallResult(
    val saved: HostProfile,
    val authPw: String,
    val effectivePasswordEdited: Boolean,
    val tunnelPw: String,
    val tunnelEd: Boolean,
    val mtlsOn: Boolean,
    val stagedP12: ByteArray?,
    val caStage: CaStage,
    val p12Password: String?,
    val p12PasswordEdited: Boolean,
    val hasMaterial: Boolean,
)

/**
 * §review-r4 (gpter R4 #3): positional-arg bundle produced by [buildTestCall]
 * and consumed by the dialog's `onTestConnection` 13-arg callback. The
 * 14th arg (`onResult: (Boolean, String) -> Unit`) is UI-side (updates
 * isTesting / testStatus) and is applied by the dialog at the call site — it
 * is not part of this snapshot bundle. Field order matches the
 * onTestConnection positional call site verbatim.
 *
 * §tofu R2: legacy `insecureSnap` dropped — `allowInsecure` no longer exists
 * on the profile; self-signed / unknown-issuer endpoints are now handled by
 * the TOFU trust dialog at first connect. The positional onTestConnection
 * call site shrank from 14→13 args accordingly.
 */
data class TestCallResult(
    val url: String,
    val userSnap: String?,
    val authPwSnap: String?,
    val profileIdSnap: String?,
    val pwEditedSnap: Boolean,
    val mtlsOn: Boolean,
    val p12Snap: ByteArray?,
    val hasMaterial: Boolean,
    val caSnap: CaStage,
    val p12Password: String?,
    val p12PasswordEdited: Boolean,
    val oldCertId: String?,
)

/**
 * §review-r4 (gpter R4 #3): pure builder for the dialog's Save `onClick`
 * snapshot. Hoisted out of the Compose `onClick` lambda so the section-off
 * credential-clearing rules (basicAuth / tunnel / mTLS), the
 * `name.ifBlank{"Untitled"}` fallback, the `selectedGroup != initialGroup`
 * groupFp rewrite, the `effectivePasswordEdited` forced-clear, and the
 * `hasMaterial` gating are all unit-testable without spinning up Compose (see
 * [MtlsDialogCallBuildersTest]).
 *
 * Returns a [SaveCallResult] whose field order matches the 11-arg `onSave`
 * positional call site (so the dialog can splat the result without
 * re-ordering).
 */
internal fun buildSaveCall(
    initial: HostProfile,
    name: String,
    serverUrl: String,
    selectedGroup: String?,
    initialGroup: String?,
    basicAuthEnabled: Boolean,
    authUsername: String,
    authPassword: String,
    passwordEdited: Boolean,
    tunnelEnabled: Boolean,
    tunnelPassword: String,
    tunnelEdited: Boolean,
    mtlsEnabled: Boolean,
    clientCleared: Boolean,
    stagedP12: ByteArray?,
    caStage: CaStage,
): SaveCallResult {
    // §mtls-clipboard: 凭据区折叠门控——区关时清空对应凭据。
    //   Basic Auth 区关：用户名/密码置空，passwordEdited 强制
    //   true（若原 profile 有 basicAuth）使 saveHostProfile 清
    //   掉 ESP 里的遗留密码。
    val effectiveUsername = if (basicAuthEnabled) authUsername else ""
    val effectiveAuthPw = if (basicAuthEnabled) authPassword else ""
    val basicAuth = effectiveUsername.ifBlank { null }
        ?.let { BasicAuthConfig(username = it, passwordId = initial.id) }
    val effectivePasswordEdited = when {
        !basicAuthEnabled -> initial.basicAuth != null
        authUsername.isBlank() && initial.basicAuth != null -> true
        else -> passwordEdited
    }
    //   隧道区关：清 tunnelId，且若原 profile 有隧道口令则强制
    //   tunnelEdited=true + 空密码使 ESP 清掉遗留口令（saveHostProfile
    //   无「无 id 即清口令」的兜底，须显式发清指令）。
    val effectiveTunnelPw: String
    val effectiveTunnelEd: Boolean
    val tunnelId = if (tunnelEnabled) {
        effectiveTunnelPw = tunnelPassword
        effectiveTunnelEd = tunnelEdited
        if (tunnelEdited) tunnelPassword.ifBlank { null }?.let { initial.id }
        else initial.tunnelPasswordId
    } else {
        effectiveTunnelPw = ""
        effectiveTunnelEd = initial.tunnelPasswordId != null
        null
    }
    val saved = initial.copy(
        name = name.ifBlank { "Untitled" },
        serverUrl = serverUrl,
        basicAuth = basicAuth,
        tunnelPasswordId = tunnelId,
        serverGroupFp = if (selectedGroup != initialGroup) {
            selectedGroup ?: initial.id
        } else {
            initial.serverGroupFp
        }
    )
    // §review-3: 同 triggerTestConnection——clientCleared 屏蔽 ESP
    // 残留旧 p12，使「移除→重开 mTLS→不粘贴→保存」hasMaterial=false
    // → resolveClientCert 返回 null → 保存被拒「需先导入客户端证书」。
    // 直接「移除→保存（mTLS 关）」仍走 Disable 清空 ESP（mtlsOn=false）。
    // §review-r4 (gpter R4 #2): hoisted into the pure [mtlsHasMaterial] predicate.
    val hasMaterial = mtlsHasMaterial(clientCleared, initial.clientCertId, stagedP12)
    return SaveCallResult(
        saved = saved,
        authPw = effectiveAuthPw,
        effectivePasswordEdited = effectivePasswordEdited,
        tunnelPw = effectiveTunnelPw,
        tunnelEd = effectiveTunnelEd,
        mtlsOn = mtlsEnabled,
        stagedP12 = stagedP12,
        caStage = caStage,
        p12Password = null,
        p12PasswordEdited = false,
        hasMaterial = hasMaterial,
    )
}

/**
 * §review-r4 (gpter R4 #3): pure builder for the dialog's `triggerTestConnection`
 * snapshot. Hoisted out of the local `fun` so the Basic-Auth-on/off branching
 * (suppress stored-password fallback when section is OFF) and the
 * `hasMaterial` gating via [mtlsHasMaterial] are unit-testable without Compose
 * (see [MtlsDialogCallBuildersTest]).
 *
 * Returns a [TestCallResult] whose field order matches the 12-arg-data part of
 * the 13-arg `onTestConnection` positional call site (the 13th arg is the
 * UI-side result callback, not part of the snapshot).
 *
 * §tofu R2: the legacy `allowInsecure` param is gone — self-signed endpoints
 * surface a TOFU trust dialog at first connect instead of a per-profile
 * trust-all toggle.
 */
internal fun buildTestCall(
    initial: HostProfile,
    serverUrl: String,
    basicAuthEnabled: Boolean,
    authUsername: String,
    authPassword: String,
    passwordEdited: Boolean,
    mtlsEnabled: Boolean,
    clientCleared: Boolean,
    initialClientCertId: String?,
    stagedP12: ByteArray?,
    caStage: CaStage,
): TestCallResult {
    val mtlsOn = mtlsEnabled
    val p12Snap = stagedP12
    val caSnap = caStage
    // §review-3: clientCleared 屏蔽 ESP 里残留的旧 p12——「移除→重开 mTLS→不粘贴」
    // 时 hasMaterial 为 false，resolveClientCert 返回 null，保存被拒（不再静默重载）。
    // §review-r4 (gpter R4 #2): hoisted into the pure [mtlsHasMaterial] predicate.
    val hasMaterial = mtlsHasMaterial(clientCleared, initialClientCertId, p12Snap)
    val oldCertId = initialClientCertId
    // §review-4: honor the Basic Auth toggle. When the section is OFF, probe with
    // NO credentials and suppress the stored-password fallback (profileId=null +
    // passwordEdited=true mirrors the Save path's section-off clearing), so we
    // don't test with creds the user believes disabled. When ON, behavior is
    // unchanged (username/password snapshotted, stored-password fallback per
    // passwordEdited).
    val userSnap: String?
    val authPwSnap: String?
    val profileIdSnap: String?
    val pwEditedSnap: Boolean
    if (basicAuthEnabled) {
        userSnap = authUsername.ifBlank { null }
        authPwSnap = authPassword.ifBlank { null }
        profileIdSnap = initial.id.takeIf { initial.basicAuth != null }
        pwEditedSnap = passwordEdited
    } else {
        userSnap = null
        authPwSnap = null
        // profileId=null + passwordEdited=true ⇒ VM 跳过已存 basic-auth 密码回退。
        profileIdSnap = null
        pwEditedSnap = true
    }
    return TestCallResult(
        url = serverUrl,
        userSnap = userSnap,
        authPwSnap = authPwSnap,
        profileIdSnap = profileIdSnap,
        pwEditedSnap = pwEditedSnap,
        mtlsOn = mtlsOn,
        p12Snap = p12Snap,
        hasMaterial = hasMaterial,
        caSnap = caSnap,
        p12Password = null,
        p12PasswordEdited = false,
        oldCertId = oldCertId,
    )
}

/**
 * §coverage-r4: pure helper hoisted out of the `triggerClientPaste` `withContext`
 * block. Decodes the clipboard base64 → validates PKCS12 via
 * [loadClientP12OrNull] → extracts the leaf-cert subject via [certSubjectOrNull].
 * Returns a `(CertSlotStatus, ByteArray?)` pair: the slot status is the user-
 * facing state (Error / Imported), and the bytes are null on error, the
 * validated PKCS12 bytes on success.
 *
 * Lives at the top level so the KDF / cert-extract branching is unit-testable
 * without spinning up Compose / coroutines (see
 * [MtlsDialogCallBuildersTest.decodeClientP12ImportTest]).
 */
internal fun decodeClientP12Import(
    raw: String,
    errBase64: String,
    errP12: String,
): Pair<CertSlotStatus, ByteArray?> {
    val bytes = decodeBase64OrNull(raw)
        ?: return CertSlotStatus.Error(errBase64) to null
    val ks = loadClientP12OrNull(bytes)
        ?: return CertSlotStatus.Error(errP12) to null
    val leafAlias = ks.aliases().asSequence().firstOrNull { ks.isKeyEntry(it) }
    val leafCert = leafAlias
        ?.let { a -> ks.getCertificateChain(a)?.firstOrNull() }
        as? java.security.cert.X509Certificate
    val subject = leafCert?.let { certSubjectOrNull(it) } ?: "client"
    return CertSlotStatus.Imported(subject, bytes.size) to bytes
}

/**
 * §coverage-r4: pure helper hoisted out of the `triggerCaPaste` `withContext`
 * block. Decodes the clipboard base64 → validates CA via [parseCaCertOrNull]
 * → extracts the cert subject via [certSubjectOrNull]. Returns a
 * `(CertSlotStatus, CaStage)` pair: the slot status is the user-facing state
 * (Error / Imported), and the stage is `Replace(bytes)` on success or
 * `Unchanged` on error (since a failed paste does not touch the existing
 * CA — the user keeps whatever was previously staged).
 *
 * Lives at the top level so the decode / parse / subject-extract branching
 * is unit-testable without spinning up Compose / coroutines (see
 * [MtlsDialogCallBuildersTest.decodeCaImportTest]).
 */
internal fun decodeCaImport(
    raw: String,
    errBase64: String,
    errCa: String,
): Pair<CertSlotStatus, CaStage> {
    val bytes = decodeBase64OrNull(raw)
        ?: return CertSlotStatus.Error(errBase64) to CaStage.Unchanged
    val cert = parseCaCertOrNull(bytes)
        ?: return CertSlotStatus.Error(errCa) to CaStage.Unchanged
    val subject = certSubjectOrNull(cert) ?: "CA"
    return CertSlotStatus.Imported(subject, bytes.size) to CaStage.Replace(bytes)
}

/**
 * §coverage-r4: pure helper hoisted out of the `triggerClientPaste` launch
 * body. Maps an import result (status, bytes) to the dialog's state writes.
 * The setters are passed in as lambdas because the dialog's `var x by
 * remember { mutableStateOf(...) }` properties are scoped to the
 * @Composable — the helper must not capture `this$0` (which would couple it
 * to the inner anonymous class and break the launch-body extraction).
 *
 * Returns `true` when the result was an `Imported` with valid bytes (so the
 * caller knows the stagedP12 / clientCleared / mtlsImportError writes
 * actually fired). The setter for clientSlotStatus fires unconditionally —
 * the dialog always wants the latest slot status reflected.
 */
internal fun applyClientP12ImportResult(
    status: CertSlotStatus,
    bytes: ByteArray?,
    setClientSlotStatus: (CertSlotStatus) -> Unit,
    setStagedP12: (ByteArray) -> Unit,
    setClientCleared: (Boolean) -> Unit,
    setMtlsImportError: (String?) -> Unit,
) {
    setClientSlotStatus(status)
    if (status is CertSlotStatus.Imported && bytes != null) {
        setStagedP12(bytes)
        // §review-3: 粘贴成功复位 Clear-signal——用户重新提供了材料。
        setClientCleared(false)
        setMtlsImportError(null)
    }
}

/**
 * §coverage-r4: pure helper hoisted out of the `triggerCaPaste` launch body.
 * Maps an import result (status, stage) to the dialog's state writes. Mirrors
 * [applyClientP12ImportResult] but for the CA slot — no Clear-signal
 * resetting on success, and the stage is forwarded verbatim on success only.
 */
internal fun applyCaImportResult(
    status: CertSlotStatus,
    stage: CaStage,
    setCaSlotStatus: (CertSlotStatus) -> Unit,
    setCaStage: (CaStage) -> Unit,
) {
    setCaSlotStatus(status)
    if (status is CertSlotStatus.Imported) {
        setCaStage(stage)
    }
}

/**
 * §coverage-r4: pure suspend helper hoisted out of the
 * `initialClientSummary` `produceState` `withContext` body. The
 * [HostViewModel.clientCertSummary] call reads ESP + runs the PKCS12 KDF —
 * it must run on [Dispatchers.Default] (per §review-3). Extracting the
 * withContext body lets the inner anonymous class for the
 * `withContext(Dispatchers.Default) { ... }` lambda collapse to a single
 * suspend call, shrinking the kover denominator.
 */
internal suspend fun summarizeClientCertOnDefault(
    viewModel: HostViewModel,
    clientCertId: String?,
): Pair<String, Int>? = withContext(Dispatchers.Default) {
    viewModel.clientCertSummary(clientCertId)
}

/**
 * §coverage-r4: pure suspend helper hoisted out of the `initialCaSummary`
 * `produceState` `withContext` body. Mirrors [summarizeClientCertOnDefault]
 * for the CA slot.
 */
internal suspend fun summarizeCaOnDefault(
    viewModel: HostViewModel,
    clientCertId: String?,
): Pair<String, Int>? = withContext(Dispatchers.Default) {
    viewModel.caSummary(clientCertId)
}

/**
 * §coverage-r4: pure decision helper hoisted out of the
 * `LaunchedEffect(initialClientSummary)` body. Decides whether the dialog
 * should seed the client-cert slot status from a freshly arrived
 * [initialClientSummary]:
 *  - null summary → no seed;
 *  - `clientEdited=true` (user already pasted/removed) → no seed
 *    (respect user intent; per §review-3, late summary must not overwrite
 *    a user-driven slot);
 *  - `clientSlotStatus` not [CertSlotStatus.Empty] → no seed
 *    (e.g. user removed an existing cert; seed would be misleading);
 *  - otherwise → seed with [CertSlotStatus.Imported].
 *
 * Returns the new status to set, or null if no seed should happen. Pure —
 * no state side-effects; the LaunchedEffect body applies the result.
 */
internal fun seedClientCertSlotStatus(
    initialClientSummary: Pair<String, Int>?,
    clientEdited: Boolean,
    currentSlotStatus: CertSlotStatus,
): CertSlotStatus? {
    val summary = initialClientSummary ?: return null
    if (!clientEdited && currentSlotStatus is CertSlotStatus.Empty) {
        return CertSlotStatus.Imported(summary.first, summary.second)
    }
    return null
}

/**
 * §coverage-r4: pure decision helper hoisted out of the
 * `LaunchedEffect(initialCaSummary)` body. Mirrors [seedClientCertSlotStatus]
 * for the CA slot — adds an extra `initialHasCa` guard so the dialog does
 * not auto-seed an Imported CA when ESP reports no stored CA (defensive
 * against the seed racing the ESP read on dialog open).
 */
internal fun seedCaSlotStatus(
    initialCaSummary: Pair<String, Int>?,
    caEdited: Boolean,
    currentSlotStatus: CertSlotStatus,
    initialHasCa: Boolean,
): CertSlotStatus? {
    val summary = initialCaSummary ?: return null
    if (!caEdited && currentSlotStatus is CertSlotStatus.Empty && initialHasCa) {
        return CertSlotStatus.Imported(summary.first, summary.second)
    }
    return null
}

/**
 * §coverage-r4: pure predicate hoisted out of the dialog's `val hasCertError`
 * composition. True iff mTLS is enabled AND at least one cert slot is in
 * [CertSlotStatus.Error]. Per §review-3, the mTLS gate is critical: when
 * mTLS is off, the cert slots are not relevant and any lingering Error
 * (e.g. from a failed paste) should NOT block Save/Test (gpter R2 non-block
 * #2).
 */
internal fun dialogHasCertError(
    mtlsEnabled: Boolean,
    clientSlotStatus: CertSlotStatus,
    caSlotStatus: CertSlotStatus,
): Boolean = mtlsEnabled && (
    clientSlotStatus is CertSlotStatus.Error || caSlotStatus is CertSlotStatus.Error
    )
