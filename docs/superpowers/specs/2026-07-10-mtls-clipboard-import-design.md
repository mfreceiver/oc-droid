# mTLS Clipboard Import + Collapsible Editor — Design

Date: 2026-07-10
Status: Approved

## Context / Why

Debugging "test connection → trust anchor path not found (caBytes=null)" concluded the
**mTLS code is correct**; the failure was an artifact of the test method:

- `adb shell input text` **splits on spaces**. PEM headers contain spaces
  (`-----BEGIN CERTIFICATE-----`), so a pasted PEM is truncated at the first space and
  segmented pastes corrupt the header. `caStage` never received valid CA bytes →
  `resolvedCa=null` → platform-CA branch → "trust anchor path not found".
- The CA field is **write-only by design** (`caPemText` derives only from `caStage`, which
  reseeds to `Unchanged` on re-entry), so a stored CA renders as an empty box.

Full chain verified correct: dialog→onSave→`resolveClientCert`→`saveClientCert`→ESP→
`loadClientCertMaterial`→`buildMutualTlsConfig`→`resolveProbe`. Storage/TLS layer untouched.

This redesign hardens the **import layer** and declutters the editor. The handshake/ESP code
is unchanged → existing profiles keep working.

## Confirmed Decisions

1. **Client credential** = upstream bundles a **passwordless PKCS12**, base64-encoded. One
   paste → decode → validate → store. Removes `pemMaterialToP12`.
2. **Server CA** = X.509 DER, base64-encoded. One paste → decode → validate → `Replace(bytes)`.
3. **Import UX** = clipboard **paste button + status** per slot (no manual typing).
4. **Basic Auth / Tunnel / mTLS** = collapsible toggles, **all OFF by default** for new profiles.
5. PKCS12 is passwordless (no password UI field) — consistent with today's internal empty password.

## Component Design

### A. `util/CertBase64.kt` (new) — tolerant base64 + cert validation

Pure JVM, unit-testable.

- `fun sanitizeBase64(s: String): String` — keep only `[A-Za-z0-9+/=]`. Strips PEM headers,
  newlines, spaces, tabs. Works for both pure-base64 and pasted-PEM inputs.
- `fun decodeBase64OrNull(s: String): ByteArray?` — sanitize → `Base64.getMimeDecoder()` decode
  with padding tolerance → bytes, or `null` on failure.
- `fun parseCaCertOrNull(bytes: ByteArray): X509Certificate?` —
  `CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes))`,
  return null on parse failure.
- `fun loadClientP12OrNull(bytes: ByteArray, password: CharArray = CharArray(0)): KeyStore?` —
  `KeyStore.getInstance("PKCS12").load(...)`, verify an aliases entry with a key + cert chain
  exists; null on failure. (PKCS12 KDF is heavy → callers run off main thread.)
- `fun certSubjectOrNull(cert: X509Certificate): String?` — `cert.subjectX500Principal.name`
  simplified to `CN=…` for status display.

### B. Validation status model

Small data class for a slot's UI state:

```
sealed interface CertSlotStatus {
  data object Empty
  data class Imported(val label: String, val sizeBytes: Int)   // "CN=…", N
  data class Error(val message: String)
}
```

### C. Dialog UI — `HostProfilesManagerScreen.kt`

Replace the two PEM `OutlinedTextField`s with a reusable **`CertImportSlot`** composable:

- **Empty**: a "📋 Paste from clipboard" button (reads `ClipboardManager` from `LocalContext`).
- **Imported**: status line `✓ <role>: <label> · <size> B` + trash (remove) button → clears the
  staged value (`stagedP12=null` or `caStage=Clear`).
- **Error**: inline `mtlsImportError`-style line.

Slots:

- Client slot: paste → `decodeBase64OrNull` → `loadClientP12OrNull` (Dispatchers.Default, reuse
  `isConverting` to disable Save/Test + spinner) → on success set `stagedP12` + status; failures
  → `Error`.
- CA slot: paste → `decodeBase64OrNull` → `parseCaCertOrNull` → on success `caStage=Replace(bytes)`
  + status; failures → `Error`.

State changes vs today:

- Remove `stagedClientPem`, `showClientPem`, `convertClientPemOrError`, the PEM `onValueChange`
  (blank→`Clear` hazard eliminated), and the client-PEM `OutlinedTextField` + CA `OutlinedTextField`.
- Keep `stagedP12` (ByteArray?), `caStage` (CaStage), `mtlsImportError`, `isConverting`.
- `onSave`/`triggerTestConnection` snapshot `stagedP12` + `caStage` as today; the
  `ClientCertEditIntent.Update(stagedP12, caStage, …)` contract is unchanged.

### D. Re-entry status from stored bytes (write-only fix)

`HostProfilesManagerScreen` already receives `initialHasCa`. Extend the VM (`HostViewModel`) /
controller so the dialog also receives, for an existing `clientCertId`:

- `clientCertSummary(id): Pair<String,Int>?` — subject + size from the stored p12 leaf cert
  (`loadClientCertMaterial(id)` → parse leaf cert subject).
- `caSummary(id): Pair<String,Int>?` — subject + size from `getClientCertCa(id)` → parse.

On re-entry, `caStage=Unchanged`/`stagedP12=null`: slots render the stored `Imported` status
(if a summary exists) instead of empty. This is display-only; resolve/save semantics unchanged.

### E. Collapsible sections

Introduce a small `CollapsibleSection(title, switch, defaultExpanded=false)` row wrapper
(Switch + AnimatedVisibility of its content). Wrap:

- **Basic Auth**: username + password fields. Switch off ⇒ auth fields hidden and treated as
  not-provided (authUsername/authPassword cleared / not emitted).
- **Tunnel password**: field hidden when off.
- **mTLS**: existing toggle already gates cert slots; align it to the same collapsible style.

New profile: all three off → editor shows Name + URL + Server group + Insecure HTTPS only.

"Insecure HTTPS" stays as its own switch (unchanged).

### F. Clipboard read

```
val ctx = LocalContext.current
val clip = ctx.getSystemService(ClipboardManager) 
val text = clip?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
```
Read on button click only; never auto-paste.

## Unchanged (low risk)

- `resolveClientCert`, `saveClientCert`, `clearClientCert`, `loadClientCertMaterial`,
  `getClientCertCa`, `buildMutualTlsConfig`, `resolveProbe`, ESP key scheme, `ClientCertMaterial`.
- `ConnectionViewModel.testConnectionForm` signature/behavior (still receives stagedP12/caStage);
  its `MTLS_DBG` log is removed.
- Existing profiles (p12 + ca bytes already in ESP) keep working — only import UI changes.

## Removed

- `pemMaterialToP12` + its file `CertPemConverter.kt` (and any now-dead helpers) — verify no other
  callers/refs.
- `convertClientPemOrError`, `stagedClientPem`, `showClientPem`, the two PEM `OutlinedTextField`s,
  `caPemText` derivation.
- 3× `MTLS_DBG` log statements (`HostProfilesManagerScreen.kt:858`, `HostProfileController.kt:265`,
  `ConnectionViewModel.kt:141`).

## Error Handling

| Failure | Message |
|---|---|
| base64 unparseable / bad padding | 无法解析为 base64 |
| PKCS12 invalid / wrong password | PKCS12 无效：<detail> |
| PKCS12 has no key entry | PKCS12 中未找到私钥 |
| CA not a valid X.509 cert | CA 证书无效：<detail> |

Save/Test disabled while `isConverting`. Errors shown inline per slot.

## Testing

- **Unit (`CertBase64Test`)**: `sanitizeBase64` over pure-base64 / PEM-with-headers / newlines /
  spaces / missing padding; `decodeBase64OrNull` round-trip; `parseCaCertOrNull` + `loadClientP12OrNull`
  with the real test materials (client p12, ca cert) committed under `app/src/test/resources`.
- **Existing**: `resolveClientCert` / `saveClientCert` tests unchanged and must still pass.
- **Manual/instrumented**: paste→status flow on emulator; re-entry shows stored status; test
  connection succeeds against a real mTLS endpoint (clipboard paste, not adb text).
- Gate: `./scripts/check.sh` (compile + unit) green; `--full` for lint.

## File-level change list

| File | Change |
|---|---|
| `util/CertBase64.kt` | NEW — sanitize/decode + cert/p12 validation |
| `util/CertBase64Test.kt` | NEW — unit tests |
| `ui/settings/HostProfilesManagerScreen.kt` | Replace PEM fields with `CertImportSlot`; collapsible Basic Auth/Tunnel sections; new profile all-off; remove PEM state + MTLS_DBG log |
| `ui/HostViewModel.kt` | Add `clientCertSummary`/`caSummary` (or via controller); pass to dialog |
| `ui/settings/HostProfilesManagerScreen.kt` call site (`SettingsScreen.kt:158`) | Wire new summary params |
| `data/repository/http/CertPemConverter.kt` | DELETE (after confirming no refs) |
| `ui/ConnectionViewModel.kt` | Remove MTLS_DBG log |
| `ui/controller/HostProfileController.kt` | Remove MTLS_DBG log |
| strings (`values*/strings.xml`) | Add paste/status/error strings |
| test resources | Add `client.p12` (passwordless), `ca.der`/base64 fixtures |

## Out of scope

- File-based cert import (clipboard only, per decision).
- PKCS12 password UI (passwordless only).
- Server-side PKCS12 generation (upstream concern; this app consumes).
