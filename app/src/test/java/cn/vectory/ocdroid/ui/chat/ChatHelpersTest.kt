package cn.vectory.ocdroid.ui.chat

import cn.vectory.ocdroid.data.api.CommandInfo
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.data.model.Part
import cn.vectory.ocdroid.data.model.PartState
import cn.vectory.ocdroid.data.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §R-19 Sprint 2 #7(b): JVM unit tests for the pure helpers lifted out of the
 * `@Composable`-heavy chat UI files (ChatScreen.kt / ChatTextParts.kt /
 * ChatInputBar.kt / ChatContextUsageDialog.kt / ChatMessageContent.kt) into
 * ChatActivityHelpers.kt / ChatTextPartHelpers.kt / ChatFormatHelpers.kt.
 *
 * Same pattern as [VisiblePickerProvidersTest]: co-locating JVM-testable
 * helpers inside a `@Composable` file hid them from kover unit-test coverage
 * (those files are excluded from the report). Each extracted helper gets
 * ≥1 happy-path + ≥1 boundary case here, targeting +1-2pp line coverage.
 */
class ChatHelpersTest {

    // ── formatThinkingFromReasoningText ────────────────────────────────────

    @Test
    fun `formatThinkingFromReasoningText with bold topic prefix yields Thinking dash topic`() {
        assertEquals("Thinking - Planning the approach", formatThinkingFromReasoningText("**Planning the approach**\nnow thinking"))
    }

    @Test
    fun `formatThinkingFromReasoningText without bold topic yields plain Thinking`() {
        assertEquals("Thinking", formatThinkingFromReasoningText("just regular reasoning text"))
    }

    @Test
    fun `formatThinkingFromReasoningText empty yields Thinking`() {
        assertEquals("Thinking", formatThinkingFromReasoningText(""))
    }

    @Test
    fun `formatThinkingFromReasoningText trims leading whitespace before matching topic`() {
        // The function trims before applying the leading-** regex.
        assertEquals("Thinking - Topic", formatThinkingFromReasoningText("   **Topic** body"))
    }

    @Test
    fun `formatThinkingFromReasoningText bold inside text is not a topic`() {
        // Only a leading ** is matched; mid-text bold is ignored.
        assertEquals("Thinking", formatThinkingFromReasoningText("text **not a topic** here"))
    }

    // ── formatStatusFromPart ───────────────────────────────────────────────

    private fun toolPart(tool: String?, state: PartState? = null, text: String? = null) =
        Part(id = "p1", type = "tool", tool = tool, state = state, text = text)

    @Test
    fun `formatStatusFromPart task tool yields Delegating`() {
        assertEquals("Delegating", formatStatusFromPart(toolPart("task")))
    }

    @Test
    fun `formatStatusFromPart todowrite and todoread both yield Planning`() {
        assertEquals("Planning", formatStatusFromPart(toolPart("todowrite")))
        assertEquals("Planning", formatStatusFromPart(toolPart("todoread")))
    }

    @Test
    fun `formatStatusFromPart read yields Gathering context`() {
        assertEquals("Gathering context", formatStatusFromPart(toolPart("read")))
    }

    @Test
    fun `formatStatusFromPart list grep glob yield Searching codebase`() {
        assertEquals("Searching codebase", formatStatusFromPart(toolPart("list")))
        assertEquals("Searching codebase", formatStatusFromPart(toolPart("grep")))
        assertEquals("Searching codebase", formatStatusFromPart(toolPart("glob")))
    }

    @Test
    fun `formatStatusFromPart webfetch yields Searching web`() {
        assertEquals("Searching web", formatStatusFromPart(toolPart("webfetch")))
    }

    @Test
    fun `formatStatusFromPart edit and write yield Making edits`() {
        assertEquals("Making edits", formatStatusFromPart(toolPart("edit")))
        assertEquals("Making edits", formatStatusFromPart(toolPart("write")))
    }

    @Test
    fun `formatStatusFromPart bash yields Running commands`() {
        assertEquals("Running commands", formatStatusFromPart(toolPart("bash")))
    }

    @Test
    fun `formatStatusFromPart unknown tool with no state yields null`() {
        assertNull(formatStatusFromPart(toolPart("unknowntool")))
    }

    @Test
    fun `formatStatusFromPart tool with topic yields Base dash topic`() {
        val state = PartState(displayString = "running", title = "Reading README.md")
        assertEquals("Gathering context - Reading README.md", formatStatusFromPart(toolPart("read", state)))
    }

    @Test
    fun `formatStatusFromPart tool falls back to inputSummary when title absent`() {
        val state = PartState(displayString = "running", inputSummary = "summary")
        assertEquals("Gathering context - summary", formatStatusFromPart(toolPart("read", state)))
    }

    @Test
    fun `formatStatusFromPart blank topic is ignored, base still returned`() {
        val state = PartState(displayString = "running", title = "   ")
        assertEquals("Gathering context", formatStatusFromPart(toolPart("read", state)))
    }

    @Test
    fun `formatStatusFromPart reasoning part delegates to formatThinkingFromReasoningText`() {
        val part = Part(id = "p1", type = "reasoning", text = "**Topic** body")
        assertEquals("Thinking - Topic", formatStatusFromPart(part))
    }

    @Test
    fun `formatStatusFromPart text part yields Gathering thoughts`() {
        val part = Part(id = "p1", type = "text", text = "hello")
        assertEquals("Gathering thoughts", formatStatusFromPart(part))
    }

    @Test
    fun `formatStatusFromPart unrecognised type yields null`() {
        val part = Part(id = "p1", type = "file")
        assertNull(formatStatusFromPart(part))
    }

    // ── bestSessionActivityText ───────────────────────────────────────────

    @Test
    fun `bestSessionActivityText status message wins outright`() {
        val status = SessionStatus(type = "busy", message = "Compiling")
        assertEquals(
            "Compiling",
            bestSessionActivityText("s1", status, emptyList(), emptyMap(), null, emptyMap())
        )
    }

    @Test
    fun `bestSessionActivityText blank status message falls through`() {
        val status = SessionStatus(type = "busy", message = "   ")
        assertEquals(
            "Thinking",
            bestSessionActivityText("s1", status, emptyList(), emptyMap(), null, emptyMap())
        )
    }

    @Test
    fun `bestSessionActivityText running tool in parts map yields tool label`() {
        val part = Part(
            id = "p1",
            type = "tool",
            tool = "read",
            state = PartState(displayString = "running")
        )
        val message = Message(id = "m1", sessionId = "s1", role = "assistant")
        val text = bestSessionActivityText(
            sessionId = "s1",
            status = null,
            messages = listOf(message),
            partsByMessage = mapOf("m1" to listOf(part)),
            streamingReasoningPart = null,
            streamingPartTexts = emptyMap()
        )
        assertEquals("Gathering context", text)
    }

    @Test
    fun `bestSessionActivityText ignores messages from other sessions`() {
        val part = Part(
            id = "p1",
            type = "tool",
            tool = "read",
            state = PartState(displayString = "running")
        )
        val other = Message(id = "m-other", sessionId = "s-other", role = "assistant")
        val message = Message(id = "m1", sessionId = "s1", role = "assistant")
        val text = bestSessionActivityText(
            sessionId = "s1",
            status = null,
            messages = listOf(other, message),
            partsByMessage = mapOf("m-other" to listOf(part)),
            streamingReasoningPart = null,
            streamingPartTexts = emptyMap()
        )
        // No matching running tool → falls through to "Thinking".
        assertEquals("Thinking", text)
    }

    @Test
    fun `bestSessionActivityText streaming reasoning yields Thinking dash topic`() {
        val reasoning = Part(id = "pr1", type = "reasoning", sessionId = "s1")
        val text = bestSessionActivityText(
            sessionId = "s1",
            status = null,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            streamingReasoningPart = reasoning,
            streamingPartTexts = mapOf("pr1" to "**Planning** stuff")
        )
        assertEquals("Thinking - Planning", text)
    }

    @Test
    fun `bestSessionActivityText streaming reasoning for other session is ignored`() {
        val reasoning = Part(id = "pr1", type = "reasoning", sessionId = "s-other")
        val text = bestSessionActivityText(
            sessionId = "s1",
            status = null,
            messages = emptyList(),
            partsByMessage = emptyMap(),
            streamingReasoningPart = reasoning,
            streamingPartTexts = mapOf("pr1" to "**Planning** stuff")
        )
        assertEquals("Thinking", text)
    }

    @Test
    fun `bestSessionActivityText retry status yields Retrying`() {
        val status = SessionStatus(type = "retry")
        assertEquals(
            "Retrying",
            bestSessionActivityText("s1", status, emptyList(), emptyMap(), null, emptyMap())
        )
    }

    @Test
    fun `bestSessionActivityText idle status with no signal yields Thinking`() {
        val status = SessionStatus(type = "idle")
        assertEquals(
            "Thinking",
            bestSessionActivityText("s1", status, emptyList(), emptyMap(), null, emptyMap())
        )
    }

    @Test
    fun `bestSessionActivityText last message part fallback yields label`() {
        // No running tool, no streaming reasoning → falls back to formatStatusFromPart
        // on the first part of the session's most recent message.
        val part = Part(id = "p1", type = "tool", tool = "bash")
        val message = Message(id = "m1", sessionId = "s1", role = "assistant")
        val text = bestSessionActivityText(
            sessionId = "s1",
            status = null,
            messages = listOf(message),
            partsByMessage = mapOf("m1" to listOf(part)),
            streamingReasoningPart = null,
            streamingPartTexts = emptyMap()
        )
        assertEquals("Running commands", text)
    }

    // ── currentSessionActivity ────────────────────────────────────────────

    @Test
    fun `currentSessionActivity with null sessionId yields null`() {
        assertNull(
            currentSessionActivity(null, null, emptyList(), emptyMap(), null, emptyMap())
        )
    }

    @Test
    fun `currentSessionActivity captures user message created time as startedAtMillis`() {
        val user = Message(
            id = "u1",
            sessionId = "s1",
            role = "user",
            time = Message.TimeInfo(created = 1_700_000_000_000L)
        )
        val assistant = Message(id = "a1", sessionId = "s1", role = "assistant")
        val activity = currentSessionActivity(
            sessionId = "s1",
            status = null,
            messages = listOf(assistant, user),
            partsByMessage = emptyMap(),
            streamingReasoningPart = null,
            streamingPartTexts = emptyMap()
        )!!
        assertEquals(1_700_000_000_000L, activity.startedAtMillis)
        assertEquals("Thinking", activity.text)
    }

    @Test
    fun `currentSessionActivity no user message yields null startedAtMillis`() {
        val activity = currentSessionActivity(
            sessionId = "s1",
            status = SessionStatus(type = "busy", message = "Working"),
            messages = emptyList(),
            partsByMessage = emptyMap(),
            streamingReasoningPart = null,
            streamingPartTexts = emptyMap()
        )!!
        assertEquals("Working", activity.text)
        assertNull(activity.startedAtMillis)
    }

    // ── fenceMarkerOf ─────────────────────────────────────────────────────

    @Test
    fun `fenceMarkerOf triple backtick returns backtick marker`() {
        assertEquals("```", fenceMarkerOf("```kotlin"))
    }

    @Test
    fun `fenceMarkerOf triple tilde returns tilde marker`() {
        assertEquals("~~~", fenceMarkerOf("~~~kotlin"))
    }

    @Test
    fun `fenceMarkerOf trims leading whitespace before matching`() {
        assertEquals("```", fenceMarkerOf("   ```kotlin"))
    }

    @Test
    fun `fenceMarkerOf non-fence line returns null`() {
        assertNull(fenceMarkerOf("regular text"))
    }

    @Test
    fun `fenceMarkerOf empty line returns null`() {
        assertNull(fenceMarkerOf(""))
    }

    @Test
    fun `fenceMarkerOf two backticks does not match three-backtick fence`() {
        assertNull(fenceMarkerOf("``not a fence"))
    }

    // ── splitCodeAndProse ─────────────────────────────────────────────────

    @Test
    fun `splitCodeAndProse empty string returns empty list`() {
        assertTrue(splitCodeAndProse("").isEmpty())
    }

    @Test
    fun `splitCodeAndProse pure prose returns single Prose segment`() {
        val segments = splitCodeAndProse("hello world\nsecond line")
        assertEquals(1, segments.size)
        assertEquals(StreamSegment.Prose("hello world\nsecond line\n"), segments.single())
    }

    @Test
    fun `splitCodeAndProse closed fence yields Prose then Code then Prose`() {
        val text = """
            before
            ```kotlin
            val x = 1
            ```
            after
        """.trimIndent()
        val segments = splitCodeAndProse(text)
        // Prose("before\n") + Code("val x = 1", "kotlin") + Prose("after\n")
        assertEquals(3, segments.size)
        assertEquals(StreamSegment.Prose("before\n"), segments[0])
        assertEquals(StreamSegment.Code("val x = 1", "kotlin"), segments[1])
        assertEquals(StreamSegment.Prose("after\n"), segments[2])
    }

    @Test
    fun `splitCodeAndProse tilde fence works like backtick fence`() {
        val text = "~~~kotlin\ncode\n~~~"
        val segments = splitCodeAndProse(text)
        assertEquals(1, segments.size)
        assertEquals(StreamSegment.Code("code", "kotlin"), segments.single())
    }

    @Test
    fun `splitCodeAndProse open fence mid-stream still emits Code segment`() {
        // §streaming-stability: an OPEN fence (EOF reached without closer) is
        // emitted as a Code segment because a growing code block is height-
        // monotonic — no prose↔code seam reflow.
        val text = "intro\n```python\ngrowing code"
        val segments = splitCodeAndProse(text)
        assertEquals(2, segments.size)
        assertEquals(StreamSegment.Prose("intro\n"), segments[0])
        assertEquals(StreamSegment.Code("growing code", "python"), segments[1])
    }

    @Test
    fun `splitCodeAndProse fence language strips pandoc attribute syntax`() {
        val text = "```{.kotlin}\ncode\n```"
        val segments = splitCodeAndProse(text)
        assertEquals(StreamSegment.Code("code", "kotlin"), segments.single())
    }

    @Test
    fun `splitCodeAndProse fence language only keeps token before first space`() {
        val text = "```kotlin args\nx\n```"
        val segments = splitCodeAndProse(text)
        assertEquals(StreamSegment.Code("x", "kotlin"), segments.single())
    }

    @Test
    fun `splitCodeAndProse open fence with no code content emits nothing for code`() {
        // No trailing newline after the fence opener → split yields exactly
        // ["intro", "```kotlin"], no empty third element, codeBuf stays empty.
        val text = "intro\n```kotlin"
        val segments = splitCodeAndProse(text)
        // prose "intro\n" emitted; codeBuf empty → no Code segment.
        assertEquals(1, segments.size)
        assertEquals(StreamSegment.Prose("intro\n"), segments.single())
    }

    @Test
    fun `splitCodeAndProse open fence with trailing newline yields Code with empty body`() {
        // A trailing \n produces an empty third line which is appended to
        // codeBuf as "\n"; the open-fence tail therefore emits a Code segment
        // whose body is "" after trimEnd('\n'). This pins the behaviour: a
        // blank line inside an open fence still counts as code content.
        val text = "intro\n```kotlin\n"
        val segments = splitCodeAndProse(text)
        assertEquals(2, segments.size)
        assertEquals(StreamSegment.Prose("intro\n"), segments[0])
        assertEquals(StreamSegment.Code("", "kotlin"), segments[1])
    }

    // ── codeText(content, startOffset, endOffset) ─────────────────────────

    private fun spanOf(content: String, start: Int = 0, end: Int = content.length) =
        codeText(content, start, end)

    @Test
    fun `codeText fenced span strips opening and closing fence lines`() {
        val content = "```kotlin\nval x = 1\nval y = 2\n```"
        assertEquals("val x = 1\nval y = 2", spanOf(content))
    }

    @Test
    fun `codeText fenced span with no language still strips fence lines`() {
        val content = "```\nplain code\n```"
        assertEquals("plain code", spanOf(content))
    }

    @Test
    fun `codeText tilde fence strips like backtick fence`() {
        val content = "~~~kotlin\ncode\n~~~"
        assertEquals("code", spanOf(content))
    }

    @Test
    fun `codeText indented code block returns span as-is`() {
        // No fence start/end → returns raw.
        val content = "    indented code line 1\n    indented code line 2"
        assertEquals(content, spanOf(content))
    }

    @Test
    fun `codeText single-line span returns as-is`() {
        // lines.size < 2 → no fence detection possible; returns raw.
        val content = "```kotlin"
        assertEquals("```kotlin", spanOf(content))
    }

    @Test
    fun `codeText respects start and end offsets`() {
        val full = "PREFIX```kotlin\nval x = 1\n```SUFFIX"
        // Span only the fenced block: offset 6 (after PREFIX) to length-6 (before SUFFIX).
        val span = full.substring(6, full.length - 6)
        assertEquals("val x = 1", codeText(span, 0, span.length))
    }

    @Test
    fun `codeText fence-only span missing closer returns raw`() {
        // lines.size >= 2, first is fence, last is NOT fence → returns raw.
        val content = "```kotlin\nval x = 1\nval y = 2"
        assertEquals(content, spanOf(content))
    }

    // ── codeFenceLanguage(content, startOffset, endOffset) ────────────────

    private fun langOf(content: String, start: Int = 0, end: Int = content.length) =
        codeFenceLanguage(content, start, end)

    @Test
    fun `codeFenceLanguage backtick fence with kotlin tag yields kotlin`() {
        assertEquals("kotlin", langOf("```kotlin\ncode\n```"))
    }

    @Test
    fun `codeFenceLanguage tilde fence with python tag yields python`() {
        assertEquals("python", langOf("~~~python\ncode\n~~~"))
    }

    @Test
    fun `codeFenceLanguage fence without language yields empty string`() {
        assertEquals("", langOf("```\ncode\n```"))
    }

    @Test
    fun `codeFenceLanguage pandoc attribute syntax strips braces and dot`() {
        assertEquals("kotlin", langOf("```{.kotlin}\ncode\n```"))
    }

    @Test
    fun `codeFenceLanguage fence with extra args keeps only first token`() {
        assertEquals("kotlin", langOf("```kotlin arg1 arg2\ncode\n```"))
    }

    @Test
    fun `codeFenceLanguage indented code block yields empty string`() {
        assertEquals("", langOf("    indented code"))
    }

    @Test
    fun `codeFenceLanguage fence longer than three backticks still extracts lang`() {
        // ````kotlin — four backticks; takeWhile eats all 4, then "kotlin".
        assertEquals("kotlin", langOf("````kotlin\ncode\n````"))
    }

    @Test
    fun `codeFenceLanguage respects start and end offsets`() {
        val full = "LEADING```kotlin\ncode\n```TRAILING"
        val span = full.substring(7, full.length - 8)
        assertEquals("kotlin", codeFenceLanguage(span, 0, span.length))
    }

    // ── handleComposerSend ────────────────────────────────────────────────

    private var sentMessage = false
    private var executedCommand: Pair<String, String>? = null
    private var compacted = false

    private fun reset() {
        sentMessage = false
        executedCommand = null
        compacted = false
    }

    private fun runSend(text: String, commands: List<CommandInfo>, allowCommand: Boolean) {
        reset()
        handleComposerSend(
            text = text,
            availableCommands = commands,
            allowCommand = allowCommand,
            onSendMessage = { sentMessage = true },
            onExecuteCommand = { cmd, args -> executedCommand = cmd to args },
            onCompact = { compacted = true }
        )
    }

    @Test
    fun `handleComposerSend known command dispatched via onExecuteCommand`() {
        runSend("/say hello", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertEquals("say" to "hello", executedCommand)
        // Falls through return; onSendMessage NOT called.
        assertEquals(false, sentMessage)
    }

    @Test
    fun `handleComposerSend unknown slash command falls through to onSendMessage`() {
        runSend("/unknown", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertNull(executedCommand)
        assertEquals(true, sentMessage)
    }

    @Test
    fun `handleComposerSend command not allowed falls through to onSendMessage`() {
        // allowCommand=false (agent busy) → even known /say becomes a prompt.
        runSend("/say hello", listOf(CommandInfo(name = "say")), allowCommand = false)
        assertNull(executedCommand)
        assertEquals(true, sentMessage)
    }

    @Test
    fun `handleComposerSend non-slash text always sends`() {
        runSend("hello there", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertNull(executedCommand)
        assertEquals(true, sentMessage)
    }

    @Test
    fun `handleComposerSend trims whitespace around slash command`() {
        runSend("   /say hello world   ", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertEquals("say" to "hello world", executedCommand)
    }

    @Test
    fun `handleComposerSend command match is case-insensitive`() {
        // The dispatcher lowercases cmdName; CommandInfo.name.equals(ignoreCase=true).
        runSend("/SAY hello", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertEquals("say" to "hello", executedCommand)
    }

    @Test
    fun `handleComposerSend slash command with no args yields empty args`() {
        runSend("/say", listOf(CommandInfo(name = "say")), allowCommand = true)
        assertEquals("say" to "", executedCommand)
    }

    @Test
    fun `handleComposerSend empty text falls through to onSendMessage`() {
        runSend("   ", emptyList(), allowCommand = true)
        assertEquals(true, sentMessage)
        assertNull(executedCommand)
    }

    @Test
    fun `handleComposerSend blank command list with slash sends as prompt`() {
        runSend("/say hi", emptyList(), allowCommand = true)
        assertEquals(true, sentMessage)
        assertNull(executedCommand)
    }

    @Test
    fun `handleComposerSend compact routes to onCompact not server command`() {
        // §compact-fix: /compact is a client-side built-in (opencode 1.17.x no
        // longer recognizes it as a prompt/command — compaction runs via the
        // summarize API). It IS in availableCommands for autocomplete but must
        // be intercepted → onCompact (summarize), NOT forwarded to /command
        // (which the server rejects with "Command not found: compact").
        runSend("/compact", listOf(CommandInfo(name = "compact")), allowCommand = true)
        assertEquals(true, compacted)
        assertNull(executedCommand)
        assertEquals(false, sentMessage)
    }

    @Test
    fun `handleComposerSend compact ignores trailing args`() {
        // compact takes no args; "/compact foo" still routes to onCompact.
        runSend("/compact foo", listOf(CommandInfo(name = "compact")), allowCommand = true)
        assertEquals(true, compacted)
        assertNull(executedCommand)
        assertEquals(false, sentMessage)
    }

    @Test
    fun `handleComposerSend compact not intercepted when disallowed (busy)`() {
        // allowCommand=false (agent busy) → /compact falls through to a prompt,
        // mirroring all other commands (compaction mid-run is also gated
        // downstream by compactSession's isCompacting guard).
        runSend("/compact", listOf(CommandInfo(name = "compact")), allowCommand = false)
        assertEquals(false, compacted)
        assertEquals(true, sentMessage)
    }

    // ── formatCount ───────────────────────────────────────────────────────

    @Test
    fun `formatCount zero`() {
        assertEquals("0", formatCount(0))
    }

    @Test
    fun `formatCount thousands grouping kicks in at 1_000`() {
        assertEquals("1,000", formatCount(1_000))
    }

    @Test
    fun `formatCount millions grouping`() {
        assertEquals("1,234,567", formatCount(1_234_567))
    }

    @Test
    fun `formatCount negative grouping`() {
        assertEquals("-1,234", formatCount(-1_234))
    }

    @Test
    fun `formatCount is locale-stable US grouping`() {
        // Pin that this is Locale.US (commas, not dots/non-breaking spaces).
        assertEquals("12,345", formatCount(12345))
    }

    // ── formatOptionalCount ───────────────────────────────────────────────

    @Test
    fun `formatOptionalCount null yields dash`() {
        assertEquals("-", formatOptionalCount(null))
    }

    @Test
    fun `formatOptionalCount zero yields zero`() {
        assertEquals("0", formatOptionalCount(0))
    }

    @Test
    fun `formatOptionalCount value delegates to formatCount`() {
        assertEquals("1,234", formatOptionalCount(1_234))
    }

    // ── markerLabelFor ────────────────────────────────────────────────────

    @Test
    fun `markerLabelFor agent-switched with agent`() {
        val msg = Message(id = "m1", role = "agent-switched", agent = "researcher")
        assertEquals("researcher", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor agent-switched without agent yields empty`() {
        val msg = Message(id = "m1", role = "agent-switched")
        assertEquals("", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor model-switched with modelId`() {
        val msg = Message(id = "m1", role = "model-switched", modelId = "claude-3")
        assertEquals("claude-3", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor model-switched falls back to model_ModelInfo_modelId`() {
        val msg = Message(
            id = "m1",
            role = "model-switched",
            model = Message.ModelInfo(providerId = "p", modelId = "from-model")
        )
        assertEquals("from-model", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor model-switched with neither yields empty`() {
        val msg = Message(id = "m1", role = "model-switched")
        assertEquals("", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor compaction yields modelId`() {
        val msg = Message(id = "m1", role = "compaction", modelId = "summary")
        assertEquals("summary", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor compaction without modelId yields empty`() {
        val msg = Message(id = "m1", role = "compaction")
        assertEquals("", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor unknown role yields empty`() {
        val msg = Message(id = "m1", role = "user")
        assertEquals("", markerLabelFor(msg))
    }

    @Test
    fun `markerLabelFor empty role yields empty`() {
        val msg = Message(id = "m1", role = "")
        assertEquals("", markerLabelFor(msg))
    }
}
