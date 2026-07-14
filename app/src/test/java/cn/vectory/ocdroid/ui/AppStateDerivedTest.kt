package cn.vectory.ocdroid.ui

import cn.vectory.ocdroid.data.model.ConfigProvider
import cn.vectory.ocdroid.data.model.HostProfile
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import cn.vectory.ocdroid.data.model.ProviderModel
import cn.vectory.ocdroid.data.model.ProviderModelLimit
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.data.model.QuestionInfo
import cn.vectory.ocdroid.data.model.QuestionRequest
import cn.vectory.ocdroid.data.model.RevertCutoff
import cn.vectory.ocdroid.data.model.RevertCutoffState
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R18 Phase 5++ coverage: pure helpers in [AppState/AppStateDerived.kt] (the
 * extracted free-function derivatives of the former AppState getters). These
 * compute the cross-slice derived views the UI needs directly from the
 * authoritative slice values; they have no Android / Compose / coroutine
 * dependencies so are pure JVM-testable.
 *
 * Coverage gap before this file: 26/116 lines (AppStateDerivedKt). This suite
 * exercises:
 *  - [currentSession] / [currentHostProfile] / [currentSessionStatus]
 *    (incl. null-current-id + missing-id branches).
 *  - [visibleMessages] + [filterBeforeRevert] (null revert / unknown revert /
 *    time-based cutoff / index fallback / tie-break / no-time-created keep).
 *  - [injectMetadataMarkers] (empty input / null→non-null no-emit / X→Y emit
 *    for both agent and model / non-user-assistant pass-through).
 *  - [computeContextUsage] (every "unavailable" reason + happy path + cache
 *    fields + total fallback from sum of components + limit<=0 rejection).
 *  - [inferCurrentModel] / [resolveModelDisplayName] (null model / unknown
 *    provider / name fallback / empty-name fallback).
 *  - [isStaleQuestionPart] (every short-circuit branch + live-question match
 *    + conservative null handling).
 */
class AppStateDerivedTest {

    // ── currentSession / currentHostProfile / currentSessionStatus ──────────

    @Test
    fun `currentSession returns null when currentSessionId is null`() {
        assertNull(currentSession(listOf(Session(id = "s1", directory = "/x")), null))
    }

    @Test
    fun `currentSession returns null when id not found`() {
        assertNull(currentSession(emptyList(), "missing"))
    }

    @Test
    fun `currentSession returns the matching session`() {
        val s = Session(id = "s1", directory = "/x")
        assertEquals(s, currentSession(listOf(s), "s1"))
    }

    @Test
    fun `currentHostProfile returns null when id is null`() {
        assertNull(currentHostProfile(listOf(HostProfile.defaultDirect("http://x")), null))
    }

    @Test
    fun `currentHostProfile returns the matching profile`() {
        val p = HostProfile(id = "p1", serverUrl = "http://x", name = "P1")
        assertEquals(p, currentHostProfile(listOf(p), "p1"))
    }

    @Test
    fun `currentSessionStatus returns null when id is null`() {
        assertNull(currentSessionStatus(mapOf("s1" to SessionStatus(type = "busy")), null))
    }

    @Test
    fun `currentSessionStatus returns null when id not in map`() {
        assertNull(currentSessionStatus(emptyMap(), "s1"))
    }

    @Test
    fun `currentSessionStatus returns the matching status`() {
        val st = SessionStatus(type = "busy")
        assertEquals(st, currentSessionStatus(mapOf("s1" to st), "s1"))
    }

    // ── visibleMessages + filterBeforeRevert ─────────────────────────────────

    @Test
    fun `visibleMessages with null session returns all messages filtered by role`() {
        val user = Message(id = "u", role = "user")
        val assistant = Message(id = "a", role = "assistant")
        val tool = Message(id = "t", role = "tool")
        val system = Message(id = "sys", role = "system")

        val out = visibleMessages(listOf(user, assistant, tool, system), null)
        assertEquals(listOf(user, assistant), out)
    }

    @Test
    fun `visibleMessages keeps synthetic metadata-marker roles`() {
        val user = Message(id = "u", role = "user")
        val agentSwitched = Message(id = "m1", role = "agent-switched")
        val modelSwitched = Message(id = "m2", role = "model-switched")
        val compaction = Message(id = "m3", role = "compaction")

        val out = visibleMessages(listOf(user, agentSwitched, modelSwitched, compaction), null)
        assertTrue(out.contains(agentSwitched))
        assertTrue(out.contains(modelSwitched))
        assertTrue(out.contains(compaction))
    }

    @Test
    fun `visibleMessages applies revert cutoff when session has revert`() {
        val s = Session(
            id = "s1", directory = "/x",
            revert = Session.RevertInfo(messageId = "m2"),
        )
        // No time.created on any message → index fallback (sublist before revert).
        val m1 = Message(id = "m1", role = "user")
        val m2 = Message(id = "m2", role = "user")
        val m3 = Message(id = "m3", role = "user")

        val out = visibleMessages(listOf(m1, m2, m3), s)
        assertEquals(listOf(m1), out)
    }

    @Test
    fun `visibleMessages fails closed from cutoff when current session is transiently missing`() {
        val before = Message(id = "before", role = "user", time = Message.TimeInfo(created = 1L))
        val after = Message(id = "after", role = "user", time = Message.TimeInfo(created = 3L))
        val cutoff = RevertCutoff("s1", "revert", RevertCutoffState.Resolved(2L))

        val out = visibleMessages(listOf(before, after), null, cutoff)

        assertEquals(listOf(before), out)
    }

    @Test
    fun `visibleMessages honors present session revert removal over stale cutoff`() {
        val messages = listOf(Message(id = "before", role = "user"), Message(id = "after", role = "user"))
        val stale = RevertCutoff("s1", "old-revert", RevertCutoffState.Failed)

        assertEquals(messages, visibleMessages(messages, Session(id = "s1", directory = "/x"), stale))
    }

    @Test
    fun `filterBeforeRevert returns input unchanged when revertId is null`() {
        val msgs = listOf(Message(id = "m1", role = "user"))
        assertEquals(msgs, msgs.filterBeforeRevert(null))
    }

    @Test
    fun `filterBeforeRevert fails closed when revertId is absent`() {
        val msgs = listOf(Message(id = "m1", role = "user"))
        assertEquals(emptyList<Message>(), msgs.filterBeforeRevert("missing"))
    }

    @Test
    fun `filterBeforeRevert time-based keeps messages strictly before revertCreated`() {
        val m1 = Message(id = "m1", role = "user", time = Message.TimeInfo(created = 100L))
        val m2 = Message(id = "m2", role = "user", time = Message.TimeInfo(created = 200L))
        val m3 = Message(id = "m3", role = "user", time = Message.TimeInfo(created = 300L))
        val list = listOf(m1, m2, m3)

        val out = list.filterBeforeRevert("m3")
        assertEquals(listOf(m1, m2), out)
    }

    @Test
    fun `filterBeforeRevert time-based excludes all messages equal to revertCreated`() {
        val m1 = Message(id = "m1", role = "user", time = Message.TimeInfo(created = 200L))
        val m2 = Message(id = "m2", role = "user", time = Message.TimeInfo(created = 200L))
        val m3 = Message(id = "m3", role = "user", time = Message.TimeInfo(created = 200L))
        val list = listOf(m1, m2, m3)

        val out = list.filterBeforeRevert("m2", RevertCutoff("s1", "m2", RevertCutoffState.Resolved(200L)))
        assertEquals(emptyList<Message>(), out)
    }

    @Test
    fun `filterBeforeRevert time-based keeps messages without created timestamp`() {
        val m1 = Message(id = "m1", role = "user")  // no time
        val m2 = Message(id = "m2", role = "user", time = Message.TimeInfo(created = 100L))
        val m3 = Message(id = "m3", role = "user", time = Message.TimeInfo(created = 200L))
        val list = listOf(m1, m2, m3)

        // m3 is revert; m1 (no time) is conservatively kept; m2 (< created) kept.
        val out = list.filterBeforeRevert("m3")
        assertTrue(out.contains(m1))
        assertTrue(out.contains(m2))
    }

    @Test
    fun `filterBeforeRevert index fallback when revert has no created timestamp`() {
        val m1 = Message(id = "m1", role = "user")
        val m2 = Message(id = "m2", role = "user")
        val m3 = Message(id = "m3", role = "user")
        val list = listOf(m1, m2, m3)

        val out = list.filterBeforeRevert("m2")
        assertEquals(listOf(m1), out)
    }

    // ── injectMetadataMarkers ────────────────────────────────────────────────

    @Test
    fun `injectMetadataMarkers empty input returns empty`() {
        assertTrue(injectMetadataMarkers(emptyList()).isEmpty())
    }

    @Test
    fun `injectMetadataMarkers does not emit on first non-null agent assignment`() {
        // null → non-null is not a "change", so no marker for the first one.
        val m1 = Message(id = "m1", role = "user")
        val m2 = Message(id = "m2", role = "assistant", agent = "code")
        val out = injectMetadataMarkers(listOf(m1, m2))
        assertEquals(listOf(m1, m2), out)
    }

    @Test
    fun `injectMetadataMarkers emits agent-switched marker when agent changes`() {
        val m1 = Message(id = "m1", role = "user", agent = "code")
        val m2 = Message(id = "m2", role = "assistant", agent = "build")
        val out = injectMetadataMarkers(listOf(m1, m2))
        assertEquals(3, out.size)
        // Marker is inserted BEFORE the differing message (m2), so order is
        // [m1, marker, m2].
        assertEquals("m1", out[0].id)
        assertEquals("agent-switched", out[1].role)
        assertEquals("build", out[1].agent)
        assertEquals("m2", out[2].id)
    }

    @Test
    fun `injectMetadataMarkers emits model-switched marker when model changes`() {
        val m1 = Message(
            id = "m1", role = "user",
            model = Message.ModelInfo(providerId = "openai", modelId = "gpt-5"),
        )
        val m2 = Message(
            id = "m2", role = "assistant",
            model = Message.ModelInfo(providerId = "anthropic", modelId = "claude"),
        )
        val out = injectMetadataMarkers(listOf(m1, m2))
        assertEquals(3, out.size)
        assertEquals("m1", out[0].id)
        assertEquals("model-switched", out[1].role)
        assertEquals("claude", out[1].modelId)
        assertEquals("anthropic", out[1].providerId)
        assertEquals("m2", out[2].id)
    }

    @Test
    fun `injectMetadataMarkers skips non-user-assistant roles for tracking`() {
        val tool = Message(id = "t", role = "tool", agent = "code")
        val assistant = Message(id = "a", role = "assistant", agent = "code")
        val out = injectMetadataMarkers(listOf(tool, assistant))
        // First non-null agent encountered on assistant → no marker (initial).
        assertEquals(listOf(tool, assistant), out)
    }

    // ── computeContextUsage ──────────────────────────────────────────────────

    @Test
    fun `computeContextUsage returns null when no assistant message exists`() {
        assertNull(computeContextUsage(listOf(Message(id = "u", role = "user")), null))
    }

    @Test
    fun `computeContextUsage returns null when assistant has no tokens`() {
        val a = Message(id = "a", role = "assistant", model = Message.ModelInfo("p", "m"))
        assertNull(computeContextUsage(listOf(a), null))
    }

    @Test
    fun `computeContextUsage returns null when assistant has no resolved model`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 100),
        )
        assertNull(computeContextUsage(listOf(a), null))
    }

    @Test
    fun `computeContextUsage returns null when no provider catalog match`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 100),
            model = Message.ModelInfo("p", "m"),
        )
        assertNull(computeContextUsage(listOf(a), ProvidersResponse()))
    }

    @Test
    fun `computeContextUsage returns null when context limit is non-positive`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 100),
            model = Message.ModelInfo("p", "m"),
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("m" to ProviderModel(id = "m", limit = ProviderModelLimit(context = 0)))),
        ))
        assertNull(computeContextUsage(listOf(a), providers))
    }

    @Test
    fun `computeContextUsage happy path returns percentage and token breakdown`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(
                total = 1000,
                input = 700,
                output = 200,
                reasoning = 100,
                cache = Message.TokenInfo.CacheInfo(read = 50, write = 10),
            ),
            model = Message.ModelInfo("p", "m"),
            cost = 0.01,
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("m" to ProviderModel(
                id = "m", name = "M", limit = ProviderModelLimit(context = 10000),
            ))),
        ))

        val usage = computeContextUsage(listOf(a), providers)
        assertNotNull(usage)
        assertEquals(0.1f, usage!!.percentage, 0.0001f)
        assertEquals(1000, usage.totalTokens)
        assertEquals(10000, usage.contextLimit)
        assertEquals("p", usage.providerId)
        assertEquals("m", usage.modelId)
        assertEquals(700, usage.inputTokens)
        assertEquals(200, usage.outputTokens)
        assertEquals(100, usage.reasoningTokens)
        assertEquals(50, usage.cachedReadTokens)
        assertEquals(10, usage.cachedWriteTokens)
        assertEquals(0.01, usage.cost!!, 0.0001)
    }

    @Test
    fun `computeContextUsage falls back to sum of components when total is null`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(
                total = null,
                input = 100,
                output = 200,
                reasoning = 50,
                cache = Message.TokenInfo.CacheInfo(read = 25, write = 5),
            ),
            model = Message.ModelInfo("p", "m"),
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("m" to ProviderModel(
                id = "m", limit = ProviderModelLimit(context = 1000),
            ))),
        ))

        val usage = computeContextUsage(listOf(a), providers)
        assertNotNull(usage)
        // 100 + 200 + 50 + 25 + 5 = 380
        assertEquals(380, usage!!.totalTokens)
    }

    @Test
    fun `computeContextUsage total of zero falls back to sum`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 0, input = 0, output = 0),
            model = Message.ModelInfo("p", "m"),
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("m" to ProviderModel(
                id = "m", limit = ProviderModelLimit(context = 1000),
            ))),
        ))

        // All zeros → sum is 0 → returns null ("no usable totals").
        assertNull(computeContextUsage(listOf(a), providers))
    }

    @Test
    fun `computeContextUsage clamps percentage to 1f when total exceeds limit`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 5000),
            model = Message.ModelInfo("p", "m"),
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("m" to ProviderModel(
                id = "m", limit = ProviderModelLimit(context = 1000),
            ))),
        ))

        val usage = computeContextUsage(listOf(a), providers)
        assertEquals(1f, usage!!.percentage, 0.0001f)
    }

    @Test
    fun `computeContextUsage matches provider via resolvedProviderId fallback`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 100),
            model = Message.ModelInfo("p-resolved", "model-x"),
        )
        // Provider entry indexed by resolvedProviderId + non-empty model.id.
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("k" to ProviderModel(
                id = "model-x",
                providerIdAlt = "p-resolved",
                limit = ProviderModelLimit(context = 1000),
            ))),
        ))

        val usage = computeContextUsage(listOf(a), providers)
        assertNotNull(usage)
        assertEquals(1000, usage!!.contextLimit)
    }

    @Test
    fun `computeContextUsage matches via unique modelId when full key not found`() {
        val a = Message(
            id = "a", role = "assistant",
            tokens = Message.TokenInfo(total = 100),
            model = Message.ModelInfo("unknown-provider", "unique-mid"),
        )
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p1", models = mapOf("k" to ProviderModel(
                id = "unique-mid",
                limit = ProviderModelLimit(context = 5000),
            ))),
        ))

        val usage = computeContextUsage(listOf(a), providers)
        assertNotNull(usage)
        assertEquals(5000, usage!!.contextLimit)
    }

    // ── inferCurrentAgent / inferCurrentModel (visibleAgents-aware) ─────────

    @Test
    fun `inferCurrentAgent skips hidden agents`() {
        // Last user msg has agent="compaction" (∉ visibleAgents {build}) → skip
        // → return the earlier user's "build".
        val msgs = listOf(
            Message(id = "u1", role = "user", agent = "build"),
            Message(id = "a1", role = "assistant"),
            Message(id = "u2", role = "user", agent = "compaction"),
            Message(id = "a2", role = "assistant", agent = "compaction"),
        )
        assertEquals("build", inferCurrentAgent(msgs, setOf("build")))
    }

    @Test
    fun `inferCurrentAgent returns null when no qualifying user message`() {
        // All user agents hidden (∉ visibleAgents) → null.
        val msgs = listOf(
            Message(id = "u1", role = "user", agent = "compaction"),
            Message(id = "a1", role = "assistant"),
        )
        assertNull(inferCurrentAgent(msgs, setOf("build")))
    }

    @Test
    fun `inferCurrentModel skips compaction assistant`() {
        // Last assistant is compaction (∉ visibleAgents {build}) → skip →
        // return the earlier visible assistant's model.
        val anthropic = Message.ModelInfo("anthropic", "claude")
        val glm = Message.ModelInfo("zhipu", "glm-4")
        val msgs = listOf(
            Message(id = "a1", role = "assistant", model = anthropic),
            Message(id = "a2", role = "assistant", agent = "compaction", model = glm),
        )
        assertEquals(anthropic, inferCurrentModel(msgs, setOf("build")))
    }

    @Test
    fun `inferCurrentModel visibleAgents variant keeps agent-null assistants`() {
        // An assistant with agent == null is always eligible (normal turn).
        val glm = Message.ModelInfo("zhipu", "glm-4")
        val msgs = listOf(
            Message(id = "a1", role = "assistant", model = glm, agent = null),
        )
        assertEquals(glm, inferCurrentModel(msgs, setOf("build")))
    }

    // ── inferCurrentModel (legacy no-filter overload) ───────────────────────

    @Test
    fun `inferCurrentModel returns null when no assistant message`() {
        assertNull(inferCurrentModel(listOf(Message(id = "u", role = "user"))))
    }

    @Test
    fun `inferCurrentModel returns the latest assistant message resolvedModel`() {
        val a1 = Message(
            id = "a1", role = "assistant",
            model = Message.ModelInfo("p1", "m1"),
        )
        val a2 = Message(
            id = "a2", role = "assistant",
            model = Message.ModelInfo("p2", "m2"),
        )
        assertEquals(Message.ModelInfo("p2", "m2"), inferCurrentModel(listOf(a1, a2)))
    }

    @Test
    fun `inferCurrentModel legacy overload keeps behavior for assistant with hidden agent`() {
        // Legacy 1-arg overload must NOT filter by agent — preserves the
        // pre-T7 behavior of the MessageActions.kt caller. An assistant
        // whose agent is "compaction" still wins as the latest assistant.
        val glm = Message.ModelInfo("zhipu", "glm-4")
        val a1 = Message(id = "a1", role = "assistant", model = Message.ModelInfo("anthropic", "claude"))
        val a2 = Message(id = "a2", role = "assistant", agent = "compaction", model = glm)
        // Legacy overload → returns a2.model (no skip); visibleAgents-aware
        // overload would have skipped a2 and returned a1.model.
        assertEquals(glm, inferCurrentModel(listOf(a1, a2)))
    }

    // ── resolveModelDisplayName ──────────────────────────────────────────────

    @Test
    fun `resolveModelDisplayName returns empty string when currentModel is null`() {
        assertEquals("", resolveModelDisplayName(null, null))
    }

    @Test
    fun `resolveModelDisplayName falls back to modelId when providers is null`() {
        val m = Message.ModelInfo("p", "raw-id")
        assertEquals("raw-id", resolveModelDisplayName(m, null))
    }

    @Test
    fun `resolveModelDisplayName falls back to modelId when provider not in catalog`() {
        val m = Message.ModelInfo("p", "raw-id")
        val providers = ProvidersResponse(providers = listOf(ConfigProvider(id = "other")))
        assertEquals("raw-id", resolveModelDisplayName(m, providers))
    }

    @Test
    fun `resolveModelDisplayName returns provider name when provider and model match`() {
        val m = Message.ModelInfo("p", "k1")
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("k1" to ProviderModel(id = "k1", name = "Friendly"))),
        ))
        assertEquals("Friendly", resolveModelDisplayName(m, providers))
    }

    @Test
    fun `resolveModelDisplayName falls back to modelId when name is empty`() {
        val m = Message.ModelInfo("p", "k1")
        val providers = ProvidersResponse(providers = listOf(
            ConfigProvider(id = "p", models = mapOf("k1" to ProviderModel(id = "k1", name = ""))),
        ))
        assertEquals("k1", resolveModelDisplayName(m, providers))
    }

    // ── isStaleQuestionPart ──────────────────────────────────────────────────

    @Test
    fun `isStaleQuestionPart returns false when part is not a tool`() {
        val part = Part(id = "p1", type = "text")
        assertFalse(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns false when tool name is not question`() {
        val part = Part(id = "p1", type = "tool", tool = "read", state = PartState(displayString = "running"))
        assertFalse(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns false when state is not running`() {
        val part = Part(
            id = "p1", type = "tool", tool = "question",
            state = PartState(displayString = "completed"),
            messageId = "m1", callId = "c1",
        )
        assertFalse(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns false when partMessageId is null`() {
        val part = Part(
            id = "p1", type = "tool", tool = "question",
            state = PartState(displayString = "running"),
            messageId = null, callId = "c1",
        )
        assertFalse(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns false when partCallId is null`() {
        val part = Part(
            id = "p1", type = "tool", tool = "question",
            state = PartState(displayString = "running"),
            messageId = "m1", callId = null,
        )
        assertFalse(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns true when no pending question matches`() {
        val part = Part(
            id = "p1", type = "tool", tool = "Question",  // case-insensitive
            state = PartState(displayString = "running"),
            messageId = "m1", callId = "c1",
        )
        assertTrue(isStaleQuestionPart(part, emptyList()))
    }

    @Test
    fun `isStaleQuestionPart returns false when a matching pending question exists`() {
        val part = Part(
            id = "p1", type = "tool", tool = "question",
            state = PartState(displayString = "running"),
            messageId = "m1", callId = "c1",
        )
        val pending = listOf(
            QuestionRequest(
                id = "q1",
                sessionId = "s1",
                tool = QuestionRequest.ToolRef(messageId = "m1", callId = "c1"),
                questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
            ),
        )
        assertFalse(isStaleQuestionPart(part, pending))
    }

    @Test
    fun `isStaleQuestionPart ignores pending questions whose tool mismatches`() {
        val part = Part(
            id = "p1", type = "tool", tool = "question",
            state = PartState(displayString = "running"),
            messageId = "m1", callId = "c1",
        )
        val pending = listOf(
            QuestionRequest(
                id = "q1",
                sessionId = "s1",
                tool = QuestionRequest.ToolRef(messageId = "OTHER", callId = "c1"),
                questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
            ),
            // tool null → returns false in the any{} block
            QuestionRequest(
                id = "q2",
                sessionId = "s1",
                tool = null,
                questions = listOf(QuestionInfo(question = "q", header = "h", options = emptyList())),
            ),
        )
        assertTrue(isStaleQuestionPart(part, pending))
    }
}
