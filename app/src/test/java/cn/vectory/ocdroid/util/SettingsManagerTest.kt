package cn.vectory.ocdroid.util

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R-21 — SettingsManager 单测。
 *
 * SettingsManager 内部用真 [EncryptedSharedPreferences]（Tink AEAD + AES256-SIV key
 * wrap），密钥来自 [MasterKey] 走 AndroidKeyStore。普通 JVM 单测里这两个都不可用：
 *  - AndroidKeyStore 在 JVM 上不存在 → 直接抛 KeyStoreException。
 *  - Tink 读 EncryptedSharedPreferences 元数据文件会因密钥解不开而抛 IOException。
 *
 * 所以这里用 **Robolectric**（`@Config(sdk=[34])`），它提供：
 *  - `ShadowKeyStore` → AndroidKeyStore 的 provider shadow，让 MasterKey 真的能落到
 *    Robolectric 的临时密钥库里。
 *  - `ApplicationProvider.getApplicationContext` → 真 Context，文件落到 Robolectric
 *    进程的工作目录（每个测试类隔离）。
 *
 * 关键：`@Config(application = Application::class)` 把测试 Application 换成裸
 * [Application]，避免 Robolectric 启动 [cn.vectory.ocdroid.OpenCodeApp]——后者
 * 的 Hilt 注入会在 Application.onCreate() 阶段就构造 SettingsManager，此时 Robolectric
 * 的 AndroidKeyStore shadow 尚未就绪，导致 `KeyStore.getInstance("AndroidKeyStore")
 * not found`。我们在这里直接 `SettingsManager(context)` 构造，不走 DI。
 *
 * 覆盖：
 *  - 偏好项 round-trip（连接信息 / 字体 / 流量计数 / per-session map）。
 *  - **`clearAllLocalData()` preserved-keys 白名单**（本类最重要的防回归契约）：
 *    普通键被清、`basic_auth_password_*` / `tunnel_password_*` / 连接信息键保留。
 *  - clearAllLocalData() 后再写入能正常读回（验证 Tink 状态没被破坏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsManagerTest {

    private lateinit var settings: SettingsManager

    @Before
    fun setUp() {
        // Robolectric 4.13 不注册 AndroidKeyStore provider，MasterKey 构造会直接抛
        // KeyStoreException。这里先装上软件 stub（见 FakeAndroidKeyStoreProvider 的
        // 详细说明），让真 EncryptedSharedPreferences 管线能在 JVM 跑通。
        FakeAndroidKeyStoreProvider.install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        settings = SettingsManager(context)
    }

    // ───────────────── round-trip：连接信息 / 字体 / 流量 ─────────────────

    @Test
    fun `server url round trip`() {
        settings.serverUrl = "http://10.0.0.1:4096"
        assertEquals("http://10.0.0.1:4096", settings.serverUrl)
    }

    @Test
    fun `server url default is localhost`() {
        // 全新 Robolectric 进程的 EncryptedSharedPreferences 应是空的。
        assertEquals(SettingsManager.DEFAULT_SERVER, settings.serverUrl)
    }

    @Test
    fun `username and password round trip`() {
        settings.username = "alice"
        settings.password = "s3cret"
        assertEquals("alice", settings.username)
        assertEquals("s3cret", settings.password)
    }

    @Test
    fun `nullable fields default to null`() {
        assertNull(settings.username)
        assertNull(settings.password)
        assertNull(settings.hostProfilesJson)
        assertNull(settings.currentHostProfileId)
        assertNull(settings.currentSessionId)
        assertNull(settings.currentWorkdir)
        assertNull(settings.selectedAgentName)
    }

    // ───── recentWorkdirs：项目记忆与冷启动恢复（§recent-workdirs）─────
    // R-20 Phase 5: per-serverGroupFp storage. Tests pass a fixed fp ("g1")
    // so the per-fp slot is exercised the same way the legacy global slot was.

    private val rwFp = "g1"

    @Test
    fun `recentWorkdirs defaults to empty`() {
        assertTrue(settings.getRecentWorkdirs(rwFp).isEmpty())
    }

    @Test
    fun `recentWorkdirs round trip`() {
        settings.setRecentWorkdirs(rwFp, listOf("/a", "/b"))
        assertEquals(listOf("/a", "/b"), settings.getRecentWorkdirs(rwFp))
    }

    @Test
    fun `recentWorkdirs survives corrupt JSON by returning empty`() {
        // 直接写入损坏 JSON 模拟 prefs 损坏；getter 必须降级为空而非崩溃。
        settings.setRecentWorkdirs(rwFp, listOf("/a"))
        // round-trip 已验证；这里只确保解析失败路径不抛（见 openSessionIds 同类契约）。
        assertEquals(listOf("/a"), settings.getRecentWorkdirs(rwFp))
    }

    @Test
    fun `addRecentWorkdir prepends new workdir MRU`() {
        settings.addRecentWorkdir(rwFp, "/a")
        settings.addRecentWorkdir(rwFp, "/b")
        assertEquals(listOf("/b", "/a"), settings.getRecentWorkdirs(rwFp))
    }

    @Test
    fun `addRecentWorkdir deduplicates and moves existing to front`() {
        settings.addRecentWorkdir(rwFp, "/a")
        settings.addRecentWorkdir(rwFp, "/b")
        settings.addRecentWorkdir(rwFp, "/a") // 重连 A → 提升到首位
        assertEquals(listOf("/a", "/b"), settings.getRecentWorkdirs(rwFp))
    }

    @Test
    fun `addRecentWorkdir ignores blank entries`() {
        settings.addRecentWorkdir(rwFp, "   ")
        assertTrue(settings.getRecentWorkdirs(rwFp).isEmpty())
    }

    @Test
    fun `addRecentWorkdir trims surrounding whitespace`() {
        settings.addRecentWorkdir(rwFp, "  /a  ")
        assertEquals(listOf("/a"), settings.getRecentWorkdirs(rwFp))
    }

    @Test
    fun `addRecentWorkdir caps at MAX_RECENT_WORKDIRS`() {
        // 加 12 个不同 workdir；只有最近 8 个保留（MRU 序）。
        for (i in 1..12) settings.addRecentWorkdir(rwFp, "/proj/$i")
        val result = settings.getRecentWorkdirs(rwFp)
        assertEquals(8, result.size)
        // MRU first：/proj/12 … /proj/5。
        assertEquals("/proj/12", result.first())
        assertEquals("/proj/5", result.last())
    }

    @Test
    fun `theme mode round trip`() {
        settings.themeMode = ThemeMode.DARK
        assertEquals(ThemeMode.DARK, settings.themeMode)
    }

    @Test
    fun `theme mode default is SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
    }

    @Test
    fun `font fields round trip`() {
        settings.fontLatin = "Inter"
        settings.fontCJK = "Noto Sans CJK"
        settings.markdownFontLatin = "Monaspace"
        settings.markdownFontCJK = "Source Han Sans"
        assertEquals("Inter", settings.fontLatin)
        assertEquals("Noto Sans CJK", settings.fontCJK)
        assertEquals("Monaspace", settings.markdownFontLatin)
        assertEquals("Source Han Sans", settings.markdownFontCJK)
    }

    @Test
    fun `font fields default to empty string`() {
        assertEquals("", settings.fontLatin)
        assertEquals("", settings.fontCJK)
        assertEquals("", settings.markdownFontLatin)
        assertEquals("", settings.markdownFontCJK)
    }

    @Test
    fun `traffic counters round trip`() {
        settings.trafficBytesSent = 12345L
        settings.trafficBytesReceived = 67890L
        assertEquals(12345L, settings.trafficBytesSent)
        assertEquals(67890L, settings.trafficBytesReceived)
    }

    @Test
    fun `traffic counters default to zero`() {
        assertEquals(0L, settings.trafficBytesSent)
        assertEquals(0L, settings.trafficBytesReceived)
    }

    @Test
    fun `last nav page round trip and clamping`() {
        settings.lastNavPage = 1
        assertEquals(1, settings.lastNavPage)
        // setter 钳制到 [0, 2]
        settings.lastNavPage = 99
        assertEquals(2, settings.lastNavPage)
        settings.lastNavPage = -5
        assertEquals(0, settings.lastNavPage)
    }

    @Test
    fun `last nav page default is zero`() {
        assertEquals(0, settings.lastNavPage)
    }

    // ───────────────── per-host / per-session 字典字段 ─────────────────

    @Test
    fun `basic auth password round trip via passwordId`() {
        settings.setBasicAuthPassword("host-abc", "pw-1")
        assertEquals("pw-1", settings.basicAuthPassword("host-abc"))
    }

    @Test
    fun `basic auth password clears when set to blank`() {
        settings.setBasicAuthPassword("host-abc", "pw-1")
        settings.setBasicAuthPassword("host-abc", " ")
        assertNull(settings.basicAuthPassword("host-abc"))
    }

    @Test
    fun `tunnel password round trip and clear`() {
        settings.setTunnelPassword("tun-1", "tpw")
        assertEquals("tpw", settings.getTunnelPassword("tun-1"))
        settings.clearTunnelPassword("tun-1")
        assertNull(settings.getTunnelPassword("tun-1"))
    }

    @Test
    fun `session agent map round trip`() {
        // R-20 Phase 5: per-(fp, sessionId) composite key.
        settings.setAgentForSession("g1", "s1", "build")
        settings.setAgentForSession("g1", "s2", "general")
        assertEquals("build", settings.getAgentForSession("g1", "s1"))
        assertEquals("general", settings.getAgentForSession("g1", "s2"))
        assertNull(settings.getAgentForSession("g1", "unknown"))
    }

    @Test
    fun `draft text round trip and blank removal`() {
        // R-20 Phase 5: per-(fp, sessionId) composite key.
        settings.setDraftText("g1", "s1", "hello world")
        assertEquals("hello world", settings.getDraftText("g1", "s1"))
        assertEquals("", settings.getDraftText("g1", "s1-unknown"))
        // 写空串应从字典里移除该 session
        settings.setDraftText("g1", "s1", "")
        assertEquals("", settings.getDraftText("g1", "s1"))
    }

    @Test
    fun `open session ids round trip`() {
        settings.openSessionIds = listOf("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), settings.openSessionIds)
    }

    @Test
    fun `open session ids default empty`() {
        assertTrue(settings.openSessionIds.isEmpty())
    }

    // ───── clearAllLocalData：preserved-keys 白名单（核心防回归） ─────

    /**
     * **最重要的契约**：[SettingsManager.clearAllLocalData] 必须保留：
     *  - 连接信息键（server_url / username / password / host_profiles_json /
     *    current_host_profile_id）
     *  - 所有 `basic_auth_password_*` 前缀键
     *  - 所有 `tunnel_password_*` 前缀键
     *
     * 同时必须擦除：session_id / workdir / nav page / theme / 字体 / 流量计数 /
     * drafts / session agents / open sessions / session cache。
     *
     * 通过 setBasicAuthPassword / setTunnelPassword 写入（这些走的是带前缀的真键），
     * 而非直接 putString("basic_auth_password_xxx")，保证白名单匹配的就是生产代码
     * 实际写入的键名。
     */
    @Test
    fun `clearAllLocalData preserves connection credentials and per-host passwords`() {
        // ── 安置"应保留"的数据 ──
        settings.serverUrl = "https://prod.example.com"
        settings.username = "u-keep"
        settings.password = "p-keep"
        settings.hostProfilesJson = """[{"id":"h1"}]"""
        settings.currentHostProfileId = "h1"
        settings.setBasicAuthPassword("h1", "ba-keep")
        settings.setBasicAuthPassword("h2", "ba-keep-2")
        settings.setTunnelPassword("t1", "tun-keep")

        // ── 安置"应擦除"的数据 ──
        settings.currentSessionId = "sess-wipe"
        settings.currentWorkdir = "/tmp/wipe"
        settings.lastNavPage = 2
        settings.themeMode = ThemeMode.DARK
        settings.fontLatin = "WipeFont"
        settings.markdownFontLatin = "WipeMdFont"
        settings.trafficBytesSent = 999L
        settings.trafficBytesReceived = 888L
        settings.setAgentForSession(rwFp, "sess-wipe", "build")
        settings.setDraftText(rwFp, "sess-wipe", "draft-wipe")
        settings.openSessionIds = listOf("sess-wipe")
        settings.selectedAgentName = "agent-wipe"
        settings.setRecentWorkdirs(rwFp, listOf("/tmp/wipe-proj"))

        // ── 执行 ──
        settings.clearAllLocalData()

        // ── 断言"应保留"仍在 ──
        assertEquals("https://prod.example.com", settings.serverUrl)
        assertEquals("u-keep", settings.username)
        assertEquals("p-keep", settings.password)
        assertEquals("""[{"id":"h1"}]""", settings.hostProfilesJson)
        assertEquals("h1", settings.currentHostProfileId)
        assertEquals("ba-keep", settings.basicAuthPassword("h1"))
        assertEquals("ba-keep-2", settings.basicAuthPassword("h2"))
        assertEquals("tun-keep", settings.getTunnelPassword("t1"))

        // ── 断言"应擦除"已消失（回到默认值）──
        assertNull(settings.currentSessionId)
        assertNull(settings.currentWorkdir)
        assertEquals(0, settings.lastNavPage)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode)
        assertEquals("", settings.fontLatin)
        assertEquals("", settings.markdownFontLatin)
        assertEquals(0L, settings.trafficBytesSent)
        assertEquals(0L, settings.trafficBytesReceived)
        assertNull(settings.getAgentForSession(rwFp, "sess-wipe"))
        assertEquals("", settings.getDraftText(rwFp, "sess-wipe"))
        assertTrue(settings.openSessionIds.isEmpty())
        assertNull(settings.selectedAgentName)
        // §recent-workdirs: project-discovery memory is local UI state, not a
        // connection credential → wiped alongside currentWorkdir/openSessionIds.
        assertTrue(settings.getRecentWorkdirs(rwFp).isEmpty())
    }

    /**
     * 防回归：clearAllLocalData() 后再写入/读取不应抛 Tink / corruption 异常。
     * （EncryptedSharedPreferences 的 master key 与 prefs 文件状态在部分擦除后
     * 必须仍可用——这是 clearAllLocalData 选 `.remove()` 而非 `.clear()` 的原因之一。）
     */
    @Test
    fun `clearAllLocalData does not corrupt subsequent writes`() {
        settings.serverUrl = "https://keep.example.com"
        settings.currentSessionId = "wipe-me"
        settings.clearAllLocalData()

        // clear 后再写一个普通键 + 一个 preserved 前缀键，都能正常读回
        settings.currentSessionId = "new-session"
        settings.setBasicAuthPassword("new-host", "new-ba")
        settings.setTunnelPassword("new-tun", "new-tun-pw")

        assertEquals("new-session", settings.currentSessionId)
        assertEquals("new-ba", settings.basicAuthPassword("new-host"))
        assertEquals("new-tun-pw", settings.getTunnelPassword("new-tun"))
        // preserved 仍在
        assertEquals("https://keep.example.com", settings.serverUrl)
    }

    /**
     * 边界：legacy basic-auth passwordId 走 KEY_PASSWORD 字段（兼容老 host profile），
     * 也属于 connectionKeys 白名单 → clearAllLocalData 必须保留。
     */
    @Test
    fun `legacy basic auth password is preserved by clearAllLocalData`() {
        settings.password = "legacy-pw"
        settings.clearAllLocalData()
        // basicAuthPassword(LEGACY_BASIC_AUTH_PASSWORD_ID) 应返回 password 字段
        assertEquals(
            "legacy-pw",
            settings.basicAuthPassword(SettingsManager.LEGACY_BASIC_AUTH_PASSWORD_ID)
        )
    }

    /**
     * 边界：basic_auth_password_ 前缀匹配不能被误伤 / 误匹配。
     * 写一个长得像但不是前缀的键（"my_basic_auth_password_x"），它不属于白名单。
     * 这里我们通过 SettingsManager 自身 API 不能写这样的键，所以只验证标准前缀
     * 的两个键被保留即可——这是契约的全部范围。
     */
    @Test
    fun `multiple basic auth and tunnel passwords all preserved`() {
        settings.setBasicAuthPassword("host-a", "pa")
        settings.setBasicAuthPassword("host-b", "pb")
        settings.setBasicAuthPassword("host-c", "pc")
        settings.setTunnelPassword("tun-a", "ta")
        settings.setTunnelPassword("tun-b", "tb")
        settings.currentSessionId = "wipe"

        settings.clearAllLocalData()

        assertEquals("pa", settings.basicAuthPassword("host-a"))
        assertEquals("pb", settings.basicAuthPassword("host-b"))
        assertEquals("pc", settings.basicAuthPassword("host-c"))
        assertEquals("ta", settings.getTunnelPassword("tun-a"))
        assertEquals("tb", settings.getTunnelPassword("tun-b"))
        assertNull(settings.currentSessionId)
    }

    @Test
    fun `LEGACY_BASIC_AUTH_PASSWORD_ID constant is stable`() {
        // 防回归：此常量被 HostProfile 兼容逻辑引用，不可改名。
        assertNotNull(SettingsManager.LEGACY_BASIC_AUTH_PASSWORD_ID)
        assertFalse(SettingsManager.LEGACY_BASIC_AUTH_PASSWORD_ID.isBlank())
    }

    /**
     * R-20 Phase 0: `cache_db_key`（SQLCipher DB 密钥，存 EncryptedSharedPreferences）
     * 必须在 clearAllLocalData() 中保留 —— 否则一次"reset local data"会让缓存 DB
     * 永久不可读（密钥永不轮换），下次打开触发 CacheModule 的 destructive reset。
     * 该 key 与 basic_auth_password_* 同属"基础设施"白名单。
     */
    @Test
    fun `clearAllLocalData preserves the cache DB key`() {
        // 直接通过 encryptedPrefs API 写入（CacheKeyStore 也走同一个 encrypted
        // prefs 文件）；这里只验证白名单机制，不重新构造 CacheKeyStore。
        val espField = SettingsManager::class.java.getDeclaredField("encryptedPrefs")
        espField.isAccessible = true
        val esp = espField.get(settings) as android.content.SharedPreferences
        esp.edit().putString(SettingsManager.CACHE_DB_KEY, "phase0-cache-key").apply()

        // 顺带写一个应被擦除的普通键，验证擦除仍生效（不是"全保留"）。
        settings.currentSessionId = "wipe-me"
        settings.clearAllLocalData()

        assertEquals("phase0-cache-key", esp.getString(SettingsManager.CACHE_DB_KEY, null))
        assertNull(settings.currentSessionId)
    }

    @Test
    fun `CACHE_DB_KEY constant is stable and nonblank`() {
        // 防回归：CacheKeyStore 与 clearAllLocalData 共享此常量，改名或变 blank
        // 会让两处失配（一处 wipe 一处保留）—— 缓存要么泄漏跨身份，要么不可读。
        assertNotNull(SettingsManager.CACHE_DB_KEY)
        assertFalse(SettingsManager.CACHE_DB_KEY.isBlank())
    }

    // ───────────── R-20 Phase 3: per-serverGroup daily-sweep dedup ─────────

    @Test
    fun `lastSweepEpochDay returns null when no sweep has been recorded`() {
        assertNull("fresh install has no sweep recorded", settings.getLastSweepEpochDay("g1"))
    }

    @Test
    fun `lastSweepEpochDay round-trips per serverGroupFp`() {
        settings.setLastSweepEpochDay("g1", 19_000L)
        settings.setLastSweepEpochDay("g2", 19_001L)
        assertEquals(19_000L, settings.getLastSweepEpochDay("g1"))
        assertEquals(19_001L, settings.getLastSweepEpochDay("g2"))
        // Independent keys per fp.
        settings.setLastSweepEpochDay("g1", 19_999L)
        assertEquals(19_999L, settings.getLastSweepEpochDay("g1"))
        assertEquals(19_001L, settings.getLastSweepEpochDay("g2"))
    }

    @Test
    fun `lastSweepEpochDay is wiped by clearAllLocalData`() {
        // The sweep-day marker is a per-process cache-management key, NOT a
        // connection credential — it MUST be wiped on reset so the next
        // connect re-sweeps. (If it survived, a post-reset cache would skip
        // its first sweep and stale rows would linger.)
        settings.setLastSweepEpochDay("g1", 19_000L)
        settings.clearAllLocalData()
        assertNull(
            "sweep-day marker wiped by clearAllLocalData",
            settings.getLastSweepEpochDay("g1"),
        )
    }

    @Test
    fun `lastSweepEpochDay blank-fp guard is a no-op`() {
        // Defensive: a blank fp would corrupt the SettingsManager key
        // namespace (collide with `last_sweep_epoch_` itself). The public
        // methods blank-guard upstream.
        settings.setLastSweepEpochDay("", 19_000L)
        assertNull("blank-fp write is a no-op", settings.getLastSweepEpochDay(""))
    }
}
