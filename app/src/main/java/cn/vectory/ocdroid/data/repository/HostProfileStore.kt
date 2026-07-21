package cn.vectory.ocdroid.data.repository

import android.util.Log
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.HostProfileExportPayload
import cn.vectory.ocdroid.data.model.HostProfileImportPayload
import cn.vectory.ocdroid.data.model.normalizeGroupFp
import cn.vectory.ocdroid.data.repository.http.hostPortFromUrl
import cn.vectory.ocdroid.util.SettingsManager
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HostProfileStore @Inject constructor(
    private val settingsManager: SettingsManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Synchronized
    fun profiles(): List<HostProfile> {
        val raw = settingsManager.hostProfilesJson
        // Decode + one-time SSH migration. On parse failure we MUST NOT
        // overwrite the original JSON — that would silently destroy data.
        val decoded = decodeProfiles(raw)
        return when (decoded) {
            is DecodeOutcome.ParseFailure -> {
                // Preserve the corrupt JSON on disk so the user can recover
                // (or a future fix-up can run). Do NOT fall through to the
                // migration path, which would overwrite with a fresh default.
                emptyList()
            }
            is DecodeOutcome.Decoded -> {
                if (decoded.profiles.isNotEmpty()) return decoded.profiles
                migrateLegacySettings()
            }
        }
    }

    @Synchronized
    fun currentProfile(): HostProfile {
        val all = profiles()
        if (all.isEmpty()) {
            // §crash-fix: profiles() returns empty on corrupt/unrecoverable
            // JSON (ParseFailure) or genuinely empty storage. Seed a fresh
            // default direct profile so this method never throws
            // NoSuchElementException. Persist it so subsequent calls are
            // stable (mirrors migrateLegacySettings). Trade-off: a corrupt
            // payload is overwritten — acceptable vs. an unusable app.
            Log.w("HostProfileStore", "profiles() returned empty (corrupt JSON or fresh install); seeding default direct profile")
            val fallback = HostProfile.defaultDirect()
            saveProfiles(listOf(fallback), fallback.id)
            return fallback
        }
        val currentId = settingsManager.currentHostProfileId
        return all.firstOrNull { it.id == currentId } ?: all.first().also {
            settingsManager.currentHostProfileId = it.id
        }
    }

    @Synchronized
    fun save(profile: HostProfile) {
        // R-20 Phase 0: enforce the nonblank serverGroupFp invariant at the
        // write boundary — defensive; new profiles and decode normalize this
        // upstream, but save() is the choke point where a blank value would
        // land on disk and silently corrupt group keying.
        val safe = profile.normalizeGroupFp()
        val all = profiles().toMutableList()
        val index = all.indexOfFirst { it.id == safe.id }
        if (index >= 0) all[index] = safe else all.add(safe)
        val currentId = settingsManager.currentHostProfileId ?: safe.id
        saveProfiles(all, currentId)
    }

    @Synchronized
    fun select(profileId: String): HostProfile {
        val all = profiles()
        val selected = all.firstOrNull { it.id == profileId } ?: error("Host profile not found")
        val updated = selected.copy(lastUsedAt = System.currentTimeMillis())
        save(updated)
        settingsManager.currentHostProfileId = selected.id
        return updated
    }

    @Synchronized
    fun duplicate(profileId: String): HostProfile {
        val source = profiles().firstOrNull { it.id == profileId } ?: error("Host profile not found")
        // R-20 Phase 0: duplicate is "clone configuration into an independent
        // connection point" — by plan §1's "import 默认新建独立组" logic, a
        // duplicate starts as its own single-member group rather than
        // inheriting the source's group. Defaulting to a fresh group avoids
        // accidental cross-contamination of the cache when the duplicate
        // points at a different server; the user may later pick A/B/C/D in
        // the editor to share intentionally.
        val newId = java.util.UUID.randomUUID().toString()
        val copy = source.copy(
            id = newId,
            name = "${source.displayName} Copy",
            lastUsedAt = null,
            serverGroupFp = newId,
            // §2.2/§2.7: 绝不继承源 profile 的 mTLS 客户端证书引用——clientCertId 是
            // 源 profile 私有的 ESP key 后缀，复制后两个 profile 共享同一证书 id 会在
            // 删除其一时 clearClientCert 把另一个也孤儿化。证书材料是设备本地敏感数据，
            // 复制配置 ≠ 复制证书；用户需在新 profile 上重新导入 p12（与导出不带 mTLS
            // 字段同语义）。
            mtlsEnabled = false,
            clientCertId = null
        )
        save(copy)
        return copy
    }

    @Synchronized
    fun delete(profileId: String) {
        val all = profiles()
        require(all.size > 1) { "Keep at least one profile" }
        val remaining = all.filterNot { it.id == profileId }
        require(remaining.size != all.size) { "Host profile not found" }
        val nextCurrent = if (settingsManager.currentHostProfileId == profileId) remaining.first().id else settingsManager.currentHostProfileId
        saveProfiles(remaining, nextCurrent)
    }

    fun exportJson(profile: HostProfile): String {
        return json.encodeToString(HostProfileExportPayload.from(profile))
    }

    fun importJson(payload: String): HostProfile {
        val profile = try {
            json.decodeFromString<HostProfileImportPayload>(payload).makeProfile()
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Invalid host profile JSON", e)
        }
        save(profile)
        return profile
    }

    /**
     * Decodes the raw JSON payload and performs a one-time migration that strips
     * legacy SSH-only profiles (`transport == "sshTunnel"`). On successful decode
     * with SSH entries removed, the cleaned list is persisted in place. On parse
     * failure, returns [DecodeOutcome.ParseFailure] so callers can preserve the
     * original payload instead of overwriting it.
     *
     * R-20 Phase 0 — `serverGroupFp` nonblank invariant: after a successful
     * decode (with or without SSH filtering), each profile whose
     * `serverGroupFp` is blank is normalized to `serverGroupFp = id`. This
     * upgrades legacy JSON that predates Phase 0 (where the field is absent →
     * defaults to `""`) into the Phase 0 form: each legacy profile becomes its
     * own single-member group, preventing a "blank group" collapse that would
     * merge unrelated legacy profiles into the same cache key (plan §0 复合
     * 键控 + freegpt #2). If normalization changed any row, the cleaned list is
     * persisted alongside the SSH cleanup (or in its own save if SSH cleanup
     * did not fire).
     */
    private fun decodeProfiles(raw: String?): DecodeOutcome {
        if (raw.isNullOrBlank()) return DecodeOutcome.Decoded(emptyList())
        return runCatching {
            val elements = json.decodeFromString<JsonElement>(raw).jsonArray
            val filtered = elements.filterNot { el ->
                runCatching {
                    el.jsonObject["transport"]?.jsonPrimitive?.content == "sshTunnel"
                }.getOrDefault(false)
            }
            val profiles = json.decodeFromJsonElement(
                ListSerializer(HostProfile.serializer()),
                JsonArray(filtered)
            )
            // R-20 Phase 0: normalize blank serverGroupFp → id (independent of
            // whether SSH filtering removed any rows). `changed` is true iff
            // any row actually had a blank group — drives the persist decision.
            val normalizedProfiles = profiles.map { p ->
                if (p.serverGroupFp.isBlank()) p.copy(serverGroupFp = p.id) else p
            }
            val changed = normalizedProfiles.zip(profiles).any { (a, b) -> a.serverGroupFp != b.serverGroupFp }
            if (filtered.size != elements.size) {
                Log.w(
                    TAG,
                    "Removed ${elements.size - filtered.size} legacy SSH profile(s) (SSH support removed)"
                )
                // Persist the cleanup. Safe: we successfully decoded every
                // remaining entry, so this is a deliberate migration rather
                // than a destructive overwrite.
                // R-20 Phase 0: persist the normalized (blank→id) form together
                // with the SSH cleanup so we don't leave blank serverGroupFp
                // rows on disk to be re-normalized on every read.
                val remainingCurrentId = settingsManager.currentHostProfileId
                    ?.takeIf { id -> normalizedProfiles.any { it.id == id } }
                saveProfiles(normalizedProfiles, remainingCurrentId)
            } else if (changed) {
                // No SSH cleanup, but blank→id normalization upgraded some rows.
                // Persist the upgraded form (independent of SSH path so legacy
                // pre-Phase-0 JSON converges on the nonblank invariant).
                Log.w(
                    TAG,
                    "Normalized ${normalizedProfiles.zip(profiles).count { (a, b) -> a.serverGroupFp != b.serverGroupFp }} " +
                        "profile(s) with blank serverGroupFp (Phase 0 migration)"
                )
                val remainingCurrentId = settingsManager.currentHostProfileId
                    ?.takeIf { id -> normalizedProfiles.any { it.id == id } }
                saveProfiles(normalizedProfiles, remainingCurrentId)
            }
            normalizedProfiles
        }.fold(
            onSuccess = { DecodeOutcome.Decoded(it) },
            onFailure = {
                Log.w(TAG, "Failed to decode host profiles JSON; preserving original payload", it)
                DecodeOutcome.ParseFailure
            }
        )
    }

    private fun migrateLegacySettings(): List<HostProfile> {
        val migrated = listOf(
            HostProfile.defaultDirect(
                serverUrl = settingsManager.serverUrl,
                username = settingsManager.username,
                passwordId = SettingsManager.LEGACY_BASIC_AUTH_PASSWORD_ID.takeIf { !settingsManager.password.isNullOrBlank() }
            )
        )
        saveProfiles(migrated, migrated.first().id)
        return migrated
    }

    private fun saveProfiles(profiles: List<HostProfile>, currentId: String?) {
        // R-20 Phase 0: belt-and-braces nonblank guard at the lowest write
        // boundary. Every other entry path (save / decodeProfiles) normalizes
        // upstream, but this catches any future caller that bypasses them.
        val safe = profiles.map { it.normalizeGroupFp() }
        settingsManager.hostProfilesJson = json.encodeToString(safe)
        settingsManager.currentHostProfileId = currentId ?: safe.firstOrNull()?.id
    }

    /**
     * §G-ACL: iterates all stored profiles, applies [migrateForGacl] to each,
     * and persists the migrated set. Returns the number of actually-migrated
     * profiles (0 if none needed migration). Idempotent: calling again on
     * already-migrated profiles returns 0.
     *
     * Call this once at app startup (e.g. from [AppCore.init]) to perform the
     * one-time legacy → G-ACL migration for existing users.
     */
    @Synchronized
    fun migrateAllForGacl(): Int {
        val all = profiles().toList()
        val migrated = all.map { it.migrateForGacl() }
        val changed = all.zip(migrated).count { (a, b) -> a.serverUrl != b.serverUrl || a.mtlsEnabled != b.mtlsEnabled }
        if (changed > 0) {
            val currentId = settingsManager.currentHostProfileId
            val migratedCurrentId = currentId?.takeIf { id -> migrated.any { it.id == id } }
            saveProfiles(migrated, migratedCurrentId)
        }
        return changed
    }

    /**
     * R-20 Phase 0: returns all profiles whose `serverGroupFp` equals [fp].
     * Empty list if [fp] matches no profile (including when [fp] is blank —
     * callers must never query for a blank group; the nonblank invariant
     * guarantees no profile carries one anyway).
     */
    @Synchronized
    fun profilesInGroup(serverGroupFp: String): List<HostProfile> {
        if (serverGroupFp.isBlank()) return emptyList()
        return profiles().filter { it.serverGroupFp == serverGroupFp }
    }

    private sealed interface DecodeOutcome {
        data class Decoded(val profiles: List<HostProfile>) : DecodeOutcome
        object ParseFailure : DecodeOutcome
    }

    private companion object {
        private const val TAG = "HostProfileStore"
    }
}
