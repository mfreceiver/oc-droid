# ocdroid — Mobile Compose (Material 3) Redesign Scheme

> **Scope.** UI/UX scheme for the new app shell + Chat / Sessions / Workspace / Settings destinations, M3-native, mobile-first, one-handed. Source of truth for the "what" is `docs/redesign-mobile-ux-architecture.md` (§5 IA, §7 phase 1–2). This document is the "how" — exact M3 components, exact code skeletons, exact line-cited removals.
>
> **Out of scope (separate work).** Controller / repository / state architecture (report §6), SSE pipeline (A4-1), AppCore god-locator (A5-2), data→ui dependency cycle (A5-1). We deliberately do not touch those here — the scheme only assumes the existing `SliceFlows` (`AppStateSlices.kt:406-429`) read surface and adds a tiny `NavRoute` enum + 1–2 derived slots.
>
> **Theme tokens.** Everything below uses `MaterialTheme.colorScheme.*`, `MaterialTheme.typography.*`, `MaterialTheme.shapes.*` per `Theme.kt:265-271`, `Type.kt:41-141`, `Shape.kt:28-34`. No new tokens. No new color literals (semantic roles use `SemanticColors.*` from existing `Color.kt` consumer code).
>
> **Compose version.** Material 3 1.2.x (per `material3WindowSizeClass = "1.2.0"` in `gradle/libs.versions.toml` + Compose BOM 2025.12.00) — `NavigationSuiteScaffold`, `SegmentedButton`, `SearchBar`, `ModalBottomSheet`, `ListItem` all available.
>
> **File references.** Scheme A (text-part) per report §8.2 — a file reference renders as a removable `AssistChip` and a literal `File: <path>` text token is inserted into the composer. Zero protocol change.

---

## (A) Target IA + adaptive strategy

### A.1  IA (text)

```
Host                            ←─ global, lives in Settings → Hosts (or via Context sheet)
└── Workspace (Workdir)         ←─ top-level chip in Chat app bar; picker = Context sheet
    ├── Conversation (Session)   ←─ top-level Chat destination
    ├── Files                    ←─ under Workspace destination
    └── Changes                  ←─ under Workspace destination

Top-level destinations (mobile):   Chat | Sessions | Workspace | Settings
Top-level destinations (expanded):  same, but Sessions + Workspace render as list-detail panes.
Settings is global-only — NO per-project state, NO VCS, NO workdir picker.
```

This is the target IA from report §5.1 / §5.2. It is the load-bearing structural change — everything else becomes easier once `Host → Workspace → Session` is rendered explicitly.

### A.2  Destination → route → composable

| Route              | M3 composable root          | Binds (slice reads)                                                                                       | Replaces                                              |
|--------------------|------------------------------|-----------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| `chat`             | `ChatScaffold`               | `ChatState`, `ComposerState`, `SessionListState`, `ConnectionState`                                       | `ChatScreen` core (`MainActivity.kt:296-353`)         |
| `sessions`         | `SessionsScreen` (existing)  | `SessionListState`, `ComposerState.draftWorkdir`                                                          | `Screen.Sessions` branch (`MainActivity.kt:354-368`)  |
| `workspace`        | `WorkspaceScaffold`          | `SessionListState.sessionDiffs`, new `WorkspaceState`                                                     | **new** — replaces `file.fileBrowserOpen` overlay     |
| `workspace/files`  | `FilesPane`                  | existing `FileState`, `FilesViewModel.state`                                                              | `FilesScreen` overlay (`MainActivity.kt:399-415`)     |
| `workspace/changes`/`workspace/changes/{file}` | `ChangesPane` / `DiffPane` | new `WorkspaceState.selectedDiffFile`            | **new** — pulls diff off Chat timeline end            |
| `settings`         | `SettingsScreen` (slim)      | `SettingsState`, `HostState`                                                                              | `Screen.Settings` (`MainActivity.kt:369-387`)         |

### A.3  Window-size-class → layout

Computed once in `MainActivity` (already done — `MainActivity.kt:176`, `LocalWindowSizeClass` `ChatScreen.kt:93`).

| Width class      | Nav surface                 | Chat / Sessions / Workspace / Settings layout                                                                                                |
|------------------|-----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| Compact <600dp   | `NavigationBar` (4 items)   | Single-pane. Bottom-sheet for "session picker", "context selector", "add menu", "agent picker", "model picker". `FilesScreen` opens as a normal `chat/files` or `workspace/files` route — not an overlay. |
| Medium 600-839dp | `NavigationRail`            | Single-pane destinations. `ModalNavigationDrawer` for session picker (left edge swipe or rail tap). Composer & top bar identical.            |
| Expanded ≥840dp  | persistent `NavigationRail` | **List-detail.** Chat shows the active conversation in full width; Sessions becomes a persistent list on the left. Workspace becomes a persistent list (Files or Changes) on the left with detail on the right. |

Implementation: `NavigationSuiteScaffold` from `androidx.compose.material3.adaptive.navigationsuite` — its `currentDestination` callback drives the right layout per class. We pass `layoutType` to override auto-detection where the canonical M3 decision disagrees with our intent (the report is explicit: we use list-detail at Expanded, not multi-pane stack).

### A.4  Removed chrome (consolidated list — every "replaces" is cited)

| Replaces                                                            | Current location (file:line)                                | Replaced by                                                          |
|---------------------------------------------------------------------|--------------------------------------------------------------|----------------------------------------------------------------------|
| 3-destination `AnimatedContent`                                     | `MainActivity.kt:287-389`                                    | `NavHost` inside `NavigationSuiteScaffold`                           |
| Browser-style session tab strip (36dp, 24dp close, scroll, hide)    | `ChatSessionTabStrip.kt:128-162, 304-399`; `ChatTopBar.kt:449-462` | A "session-history" `IconButton` in the new `TopAppBar` + bottom sheet (compact) / persistent list (expanded) |
| HorizontalPager swipe between sessions                              | `ChatScreen.kt:399-414, 615-655`                             | Tap-to-select from session picker; swipe is reserved for transcript/horizontal-scroll content (no gesture collision) |
| `+` button that immediately opens the image picker                  | `ChatInputBar.kt:167-197`                                    | `+` button opens `ModalBottomSheet` Add-menu (Photos / File ref / Commands) |
| Host switcher behind DNS icon → dialog                              | `ChatTopBar.kt:386-428` (DNS icon) + `ChatServerManagementDialog` for the dialog itself | Context chip in app bar (host → workdir) → unified `ModalBottomSheet` (ContextSelectorSheet) |
| Workdir switcher buried in `SessionsScreen`                         | `SessionsScreen.kt:218-228` (folder icon), `:343-374` (AddComment) | Context chip → `ContextSelectorSheet`                                |
| Agent + Model hidden behind the context-usage ring → 4 nested dialogs | `ChatSessionTabStrip.kt:405-515`; `ChatTopBar.kt:466-602`     | Two chips directly above the composer (Agent / Model), each opens a searchable `ModalBottomSheet` |
| `FileState.fileBrowserOpen` overlay                                | `MainActivity.kt:399-415`                                    | `workspace/files` route inside `NavigationSuiteScaffold`             |
| VCS section in Settings                                            | `SettingsScreen.kt:307-322, 379-557` (`VcsSection`)          | `workspace/changes` route (full review + diff)                       |
| Custom 3-pill theme selector + custom dot-slider for font/zoom     | `SettingsSections.kt:165-263` (already M3 `SegmentedButton` for theme + `Slider` — this entry is "preserve and confirm") | n/a — already aligned; just moves into the slim Settings sub-routes |
| Five competing top-center overlays (Thinking / Retry / Compacting / Connecting / Question) | `ChatScreen.kt:700-770, 816-834`; `ChatMessageContent.kt:572-606` | One **Single Status Slot** (see C.3) — only one of those five is visible at any time, plus the question card moves to a stable "needs action" zone above the composer |

---

## (B) Component inventory — every new / changed M3 component

Each row says the M3 API, what it replaces, and the line citation. Skeletons live in section (D).

### B.1  App shell

| M3 component                                  | Replaces (file:line)                                            | Notes |
|-----------------------------------------------|-----------------------------------------------------------------|-------|
| `NavigationSuiteScaffold` (`adaptive.layout.NavigationSuiteScaffold` in Compose 1.2; alias `NavigationSuiteScaffold` in M3) | `Scaffold` + `AnimatedContent` (`MainActivity.kt:283-389`) | `navigationSuiteItems { … }` with 4 entries; `currentDestination` reads from the `NavController`'s current entry. `layoutType` parameter overrides M3's default Expanded=TwoPane to `ThreePane` (list-detail) for `workspace` route. |
| `NavigationBar` (auto) / `NavigationRail` (auto) | none — adds what is missing                                  | Driven by `WindowSizeClass`. Items are `NavigationBarItem` / `NavigationRailItem`. |
| `NavHost` (from `androidx.navigation:navigation-compose:2.7.6`, already in `gradle/libs.versions.toml`) | none — adds what is missing                                  | Real route graph; predictive back via `PredictiveBackHandler` (M3 1.2+ has stable `androidx.activity:activity-compose` integration). |
| `BackHandler` + `PredictiveBackHandler`        | multiple ad-hoc `BackHandler` blocks (`MainActivity.kt:269-281, 401, 849`; `ChatScreen.kt:213, 227-235`; `FilesScreen.kt:69-71`) | One per route in the shell; sheet-level `BackHandler` only when the sheet is the only intercept (predictive-back already handles the route). |
| `Scaffold` (single, for chat)                 | `Scaffold` + Surface card (`ChatScreen.kt:284, 593-603`)        | The `Scaffold` is the shell. Inside the `Chat` route, a child `Scaffold` carries the `TopAppBar` (M3 1.2 supports nested scaffolds cleanly). |

### B.2  Chat screen

| M3 component                              | Replaces (file:line)                                                                                   | Notes |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------|-------|
| `TopAppBar` (single, no tab strip)        | `TopAppBar` + 36dp `PrimaryScrollableTabRow` (second row) `ChatTopBar.kt:228-463`                      | `navigationIcon` = session-history; `title` = conversation title (no workdir subtitle — that moves to the context chip); `actions` = context chip + overflow `DropdownMenu` (rename / archive / copy link). |
| `ModalBottomSheet` (session picker)       | the 36dp `SessionTabStrip` + the `DropdownMenu` of "open" sessions in `ChatTopBar.kt` (second row)     | M3 1.2 stable API; sheet state via `rememberModalBottomSheetState(skipPartiallyExpanded = true)`. |
| `SearchBar` (inside session picker sheet) | the `SessionsScreen`'s lack of search (`SessionsScreen.kt:182-413`)                                      | Compose 1.5+ stable; `docked` mode for inline list filtering. |
| `ListItem` (session rows in picker)       | `SessionCard` custom surface (`SessionsScreen.kt:522-634`)                                              | Use the same `ListItem` already in use for session rows; just lifted into a sheet. |
| `ExtendedFloatingActionButton` (new session) | a "+" affordance that used to live in the top-bar of `SessionsScreen.kt:218-228`                     | M3 FAB spec: 16dp extended height; label "New session". |
| `DropdownMenu` (overflow)                 | the merged `ContextMenuButton` with 4 `DropdownMenuItem`s (`ChatSessionTabStrip.kt:411-515`)            | Renamed to "Conversation actions": Rename / Archive / Copy link / Disconnect (if sub-agent). Destructive items colored via `DropdownMenuItemDefaults` if available; otherwise M3 `colorScheme.error`. |
| `AssistChip` (context chip in app bar)    | the workdir-basename subtitle (`ChatTopBar.kt:317-338`) + the DNS icon host badge (`ChatTopBar.kt:386-428`) | Single chip showing "Host → Workdir basename". Tap → `ContextSelectorSheet`. Width auto-sizes to the label; long paths ellipsize. |
| `IconButton` 48dp (session-history)       | the hamburger `IconButton` for `Icons.Filled.Menu` (`ChatTopBar.kt:239-245`)                            | `Icons.AutoMirrored.Filled.Menu` for the LTR-default; the action moves to the navigation rail, so on compact this stays as the only entry point. |

### B.3  Transcript

| M3 component                            | Replaces (file:line)                                                  | Notes |
|-----------------------------------------|------------------------------------------------------------------------|-------|
| `ElevatedCard` (message bubble)         | custom 220dp/2-3 cap card in `MessageRow` (`ChatMessageRow.kt:82-86`) | M3 `ElevatedCard` per role: agent = `surfaceContainer`, user = `surfaceContainerHigh`, system = `surfaceContainerLowest`. |
| `ListItem` (message header line)        | the missing "speaker label" (`ChatMessageRow.kt:90-92` — comment "no OpenCode speaker title … already make it clear who's speaking") | `leadingContent` = avatar (agent icon / user Person / system Info), `headlineContent` = sender name, `supportingContent` = role + time. |
| `Card` (tool activity group)            | the "fold bar" + accordion (`ToolCallFoldBar.kt`, `ToolCallFoldGrouper.kt`) | Keep `FoldedToolRun` group as-is internally; render as a single compact `Card` with row of icons + count. |
| `ListItem` + `Badge` (N files changed)  | the `SessionDiffCard` at the bottom of the transcript (`ChatMessageContent.kt:596-606`) | Deep-link row: tap → `navController.navigate("workspace/changes?session=<id>")`. |
| `IconButton` + `DropdownMenu` (row overflow) | long-press / hidden gestures on messages                                | Per report P4-4 / V8 / P5-5. Tap = no-op (selection only); overflow = Edit & rerun / Fork / Copy / Revert to this point. |

### B.4  Composer

| M3 component                            | Replaces (file:line)                                                  | Notes |
|-----------------------------------------|------------------------------------------------------------------------|-------|
| `Surface` (composer card)               | custom `Surface` w/ `RectangleShape` in `ChatInputBar.kt:134-139`     | Keep the user's "no rounded corners" choice: `shape = RectangleShape`. |
| `AssistChip` (Agent chip)               | the dropdown `ContextMenuButton` entry + standalone `AlertDialog` (`ChatSessionTabStrip.kt:486-496`; `ChatTopBar.kt:501-563`) | Lives directly above the input row. Tap → `ModalBottomSheet` agent picker (searchable). |
| `AssistChip` (Model chip)               | the model dropdown entry + `ModelPickerDialog` (`ChatSessionTabStrip.kt:497-513`; `ChatTopBar.kt:591-602`) | Same pattern. |
| `IconButton` 48dp (`+` Add)             | the existing `Box.size(48.dp).clickable` `+` (`ChatInputBar.kt:183-197`) | Replace with M3 `IconButton` (48dp) + `Icons.Default.Add`. Tap → `ModalBottomSheet` Add-menu. |
| `BasicTextField` (input)                | the existing `BasicTextField` (`ChatInputBar.kt:219-233`)             | No change. Keep slash-command autocomplete (existing `CommandSuggestionsPanel` at `ChatInputBar.kt:301-345` moves INTO the sheet on Expanded; stays inline on compact). |
| `IconButton` 48dp (Send/Stop)           | `ChatPrimaryActionButton` (`ChatInputBar.kt:347-385`)                 | M3 `IconButton` 48dp with `Icons.AutoMirrored.Filled.Send` / `Icons.Default.Stop`. |
| `ModalBottomSheet` (Add menu)           | the immediate `ActivityResultContracts.GetMultipleContents` launch (`ChatInputBar.kt:155-161`) | Three rows: Photos (opens image picker), Reference workspace file, Commands. |
| `ModalBottomSheet` (Agent picker)       | the standalone `AlertDialog` agent picker (`ChatTopBar.kt:501-563`)   | Adds `SearchBar` for filtering. |
| `ModalBottomSheet` (Model picker)       | `ModelPickerDialog` (`ChatTopBar.kt:622-687`)                         | Adds `SearchBar` for filtering; groups by provider. |
| `InputChip` (file reference chip)       | the bare text-insert path                                             | Renders `📎 <basename>` with trailing `×` to remove. Renders above the input row (between chips and text). |

### B.5  Session picker (replaces tab strip)

| M3 component              | Notes                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------|
| `ModalBottomSheet`        | Anchored from the app-bar session-history icon. Sheet contains the `SearchBar` + `ListItem` list. |
| `ModalNavigationDrawer`   | Optional alternative for Medium width.                                                        |
| `SearchBar`               | Top of the sheet, filters the list in-place.                                                  |
| `ListItem`                | Each row: agent/workdir tone color (existing `workdirTone` from `AgentTone.kt:38-41`) as `leadingContent` icon tint, title, subtitle (workdir + time), trailing = status dot + `DropdownMenu` trigger. |
| `ExtendedFloatingActionButton` | "New session" — bottom-end of the sheet.                                                |
| `HorizontalDivider`       | Section dividers between Recent / Needs action / By workspace.                                 |

### B.6  Context selector sheet

| M3 component              | Notes                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------|
| `ModalBottomSheet`        | The single entry point. Header shows current Host → Workdir. Body has sections (see D.4).       |
| `ListItem`                | One per host / workdir. `trailingContent` = radio button (`RadioButton`) or `Switch` for "set as default workdir". |
| `SearchBar`               | For hosts (small list, but the canonical affordance).                                          |
| `Button`                  | "Connect project" → opens existing `DirectoryPickerSheet` (`DirectoryPicker.kt:67`); "Manage hosts" → `navController.navigate("settings/hosts")`. |

### B.7  Workspace destination (new)

| M3 component              | Notes                                                                                          |
|---------------------------|------------------------------------------------------------------------------------------------|
| `Scaffold` + `TopAppBar`  | Title = "Workspace" or workdir basename; `navigationIcon` = back to wherever we came from.     |
| `PrimaryTabRow` / `Tab`   | Two tabs: Files / Changes. Replaces the per-screen TopAppBar-style tabs everywhere.            |
| `SearchBar` (Files)       | Replaces the missing search in `FilesScreen.kt:130-138`.                                       |
| `ListItem` (file rows)    | Already in use (`FileBrowserPane.kt:57-83`); just relocated into the route.                    |
| `ListItem` (changed file rows in Changes) | Reuses `VcsStatusRow` visual (`SettingsScreen.kt:505-557`) but in a `ListItem`.  |
| `FilterChip` row          | Status filter for both panes: All / Modified / Added / Deleted / Untracked.                    |
| `NavigationRail` item "Changes" badge | A small `Badge` showing pending-changes count, projected from `SessionListState.sessionDiffs`. |
| `ModalBottomSheet` (Diff) | When a file row is tapped, slide up a `ModalBottomSheet` with the unified diff (no side-by-side on compact). |

### B.8  Settings cleanup

| M3 component                              | Replaces (file:line)                                                                                  | Notes |
|-------------------------------------------|--------------------------------------------------------------------------------------------------------|-------|
| `ListItem` (every section row)            | the `Card` + `SectionHeader` + body pattern in `SettingsScreen.kt:184-263`                             | A `ListItem` with `headlineContent` = section name, `supportingContent` = 1-line summary, `trailingContent` = chevron. |
| `SegmentedButton` (theme)                 | already M3 (`SettingsSections.kt:183-213`) — confirm and keep                                        | n/a   |
| `Slider` (font / content scale)           | already M3 (`SettingsSections.kt:228-260`) — confirm and keep                                        | n/a   |
| Sub-routes (`settings/hosts`, `settings/appearance`, `settings/models`, `settings/notifications`, `settings/storage`, `settings/about`) | single scrollable Settings (`SettingsScreen.kt:184-263`)                                            | A top-level Settings screen becomes a list of section `ListItem`s; each pushes its sub-route onto the same `NavHost`. |
| `Card` (the sub-screen body)              | `Card` with body content per `SettingsSections.kt`                                                    | Reuse the existing `*Section` composables. |
| `ModalBottomSheet` (Notifications quick-toggles) | n/a — currently absent                                                                | Add a notifications sub-route if/when needed. |

---

## (C) Layout & spacing specs

### C.1  Touch targets (WCAG 2.5.5)

- **Minimum 48dp** for every interactive surface. The current app is partially compliant: 48dp is the M3 default, but several explicit `Modifier.size(24.dp)` / `size(28.dp)` / `size(40.dp)` exist:
  - 24dp tab close `ChatSessionTabStrip.kt:387-399` — **removed entirely** in the new shell (close moves to row overflow).
  - 28dp visual send `ChatInputBar.kt:347-385` — becomes a 48dp `IconButton`.
  - 32dp workdir folder / add-comment `SessionsScreen.kt:343-374` — already migrated to 48dp in code; confirm in audit.
- All `IconButton`s without an explicit `size` default to 48dp — keep that.
- All `ListItem` default height is 56dp (one-line) or 72dp (two-line) — keep that; do not override.
- The `SegmentedButton` row is full-width and 40dp tall — fine (it's a continuous control, not a tappable item).
- The `ExtendedFAB` ("New session") is 56dp — fine.

### C.2  Thumb-zone placement (one-handed)

- **Critical actions in the bottom 40%** of the screen: composer (full width bottom), FAB (bottom-end, 16dp margin from nav bar).
- **Contextual / frequent in the top edge**: app bar (44dp above the screen top, status-bar inset below).
- **Avoid in the top 20%** anything that requires repeated tapping (e.g. session-history lives in the app bar because it's a deliberate action, not a frequent one).
- The `NavigationBar` items (compact) are within thumb reach — fine.
- The overflow `DropdownMenu` (`Icons.Default.MoreVert`) lives in the app bar (top-right) but is for occasional / destructive actions — fine.

### C.3  Single Status Slot — priority rule

The top-center of the chat area is the "agent needs attention" surface. Only ONE of the following can be visible at a time. The priority order is the binding rule:

1. **Permission** (incoming, requires user action)
2. **Question** (incoming, requires user answer)
3. **Retry / error** (failed run, recoverable)
4. **Running / compacting** (agent is producing)
5. **Connecting** (network transitional)

Rule:

- The visible status surface = the highest-priority item whose `isActive == true`.
- Permissions and questions are scoped to the current session (report P5-7): `sessionListState.pendingPermissions.filter { it.sessionId == chat.currentSessionId }` + `sessionListState.pendingQuestions.filter { it.sessionId == chat.currentSessionId }` — this is the same filter already in `ChatScreen.kt:419-422`, just made the canonical rule.
- Cross-session pending items (different `sessionId`) surface as a `Badge` on the `Sessions` nav-bar item, not as an overlay in the chat.
- The `ThinkingCapsule` (`ChatScreen.kt:700-770`), the `SessionRetryCard` (`ChatScreen.kt:733`), and the `QuestionCardView` (`ChatScreen.kt:751-771`) currently all stack — the new shell renders at most one (plus the connection state if it's the highest-priority active one and is its own dedicated bottom snackbar area).

Replaces: `ChatScreen.kt:687-770, 816-834` (overlapping `ThinkingCapsuleOverlay` + `SessionRetryCard` + connecting capsule + question card).

### C.4  Edge-to-edge + safe areas

- `enableEdgeToEdge()` already in `MainActivity.kt:109` — keep.
- `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars = !darkTheme` in `Theme.kt:230-234` — keep.
- The `Scaffold` (shell) uses `contentWindowInsets = WindowInsets(0)` (already `MainActivity.kt:285`); the `TopAppBar` self-handles status bar via its default `TopAppBarDefaults.windowInsets`.
- The `NavigationBar` self-handles navigation-bar inset via its default `WindowInsets.navigationBars`.
- The composer uses `Modifier.imePadding()` (already `ChatInputBar.kt:135`).
- Do **not** apply `statusBarsPadding()` on the inner `Column` (per `ChatScreen.kt:248-250` — already correct).

### C.5  Motion

- **Sheet enter/exit**: M3 `ModalBottomSheet` default spring (`AppMotion.standardSpring`) — already in `ui/theme/AppMotion`. Keep.
- **Tab fade (Workspace Files/Changes)**: `AnimatedContent(targetState = tabIndex, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "workspaceTab")`.
- **Nav-bar crossfade (destination switch)**: `AnimatedContent` with `fadeIn(AppMotion.standardSmall) togetherWith fadeOut(AppMotion.standardSmall)` (already in `MainActivity.kt:289`).
- **Status-slot swap**: `AnimatedContent` with `fadeIn(150ms) + slideInVertically(initialOffsetY = { -it/4 })` vs `fadeOut(120ms) + slideOutVertically(targetOffsetY = { -it/4 })`. The previous behavior used a hard `if/else` with no transition — this is the deliberate fix for "decisions compete for the same pixel".
- **Card hover / press** (Extended FAB, IconButton, ListItem): M3 default ripple via `LocalIndication.current`.
- **Predictive back**: route-aware shell `PredictiveBackHandler` for top destinations; sheet-level `BackHandler` only for open sheets.

### C.6  Spacing tokens (use existing — do not introduce new)

- App bar content inset: `WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)` (TopAppBar default).
- Card / ListItem vertical: `8.dp` between stacked items, `12.dp` between sections.
- Composer horizontal padding: `16.dp` (current `ChatInputBar.kt:143`).
- Chat transcript horizontal padding: `16.dp` per message bubble; `0.dp` for the LazyColumn edges (per `ChatMessageRow.kt:83`).
- Sheet handle → first content: `16.dp`.
- Bottom FAB → bottom edge: `16.dp` from the nav-bar inset.

### C.7  Shape tokens (per `Shape.kt:28-34`)

- Cards: `MaterialTheme.shapes.medium` (8dp) for messages, `MaterialTheme.shapes.large` (10dp) for the message-list container on medium/expanded.
- Chips: `MaterialTheme.shapes.small` (6dp) — M3 default for `AssistChip` / `InputChip`.
- Sheets: `MaterialTheme.shapes.extraLarge` (12dp top corners) — M3 default.
- The user's "no rounded corners" composer preference: keep `RectangleShape` for the composer card and the chips-row background (current `ChatInputBar.kt:137`).

### C.8  Typography (per `Type.kt:41-141`)

- App bar title: `titleLarge` (18sp/24sp) — current `ChatTopBar.kt:319`.
- Conversation header in `MessageCard`: `titleSmall` (14sp/21sp medium).
- Message body: `bodyLarge` (14sp/21sp normal) — current default.
- Sub-titles / metadata: `bodySmall` (12sp/16sp) / `labelSmall` (12sp/16sp medium).
- Top status slot: `labelLarge` (14sp/20sp medium).
- No new fonts.

### C.9  Color roles

- Message role surface tints:
  - Agent (assistant): `colorScheme.surfaceContainer` (default).
  - User: `colorScheme.surfaceContainerHigh` (slightly raised).
  - System / metadata: `colorScheme.surfaceContainerLowest` (flat).
- Sender name color: `colorScheme.onSurface` for agent/user; `colorScheme.onSurfaceVariant` for system.
- Agent / workdir avatar tint: existing `agentTone` / `workdirTone` from `AgentTone.kt:23-41` (16-color palette in `SemanticColors`).
- Status slot colors: `SemanticColors.stateSuccessFg()` (connected), `SemanticColors.stateInfoFg()` (connecting), `colorScheme.error` (disconnected / failed) — already the convention in `ChatTopBar.kt:400-405`.
- Connection badge (nav-bar Sessions icon, not chat): same palette as above.

---

## (D) Compose code skeletons

> All skeletons are compilable-ish: imports + composable signature + body that binds to the existing state slices. They are deliberately **not** wire-up complete — the wire-up is described in (F). Strings are placeholder; copy review happens later.

### D.1  `AppShell` — `NavigationSuiteScaffold` + `NavHost`

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.1
// Package kept as it would land: cn.vectory.ocdroid.ui.shell
package cn.vectory.ocdroid.ui.shell

import androidx.compose.material3.*
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import cn.vectory.ocdroid.ui.NavRoute
import cn.vectory.ocdroid.ui.chat.ChatScaffold
import cn.vectory.ocdroid.ui.sessions.SessionsScreen
import cn.vectory.ocdroid.ui.workspace.WorkspaceScaffold
import cn.vectory.ocdroid.ui.settings.SettingsScaffold
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.SessionViewModel
import cn.vectory.ocdroid.ui.SettingsViewModel
import cn.vectory.ocdroid.ui.ConnectionViewModel
import cn.vectory.ocdroid.ui.HostViewModel
import cn.vectory.ocdroid.ui.ComposerViewModel
import cn.vectory.ocdroid.ui.ChatViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Top-level shell. Replaces MainActivity.kt:283-389 (custom AnimatedContent + 3 Screen values)
 * and MainActivity.kt:399-415 (fileBrowserOpen overlay).
 *
 * State surface it reads (orchestrator-backed):
 *   - connection (for the "Connecting" status slot)
 *   - sessionList.pendingPermissions / pendingQuestions (cross-session inbox badge)
 *   - sessionList.sessionDiffs (cross-session "N files changed" badge on Workspace)
 *
 * It does NOT read any chat or composer state — those are wired inside ChatScaffold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    orchestratorVM: OrchestratorViewModel,
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route?.substringBefore("/")
        ?: NavRoute.Chat.route

    val connection by orchestratorVM.connectionFlow.collectAsStateWithLifecycle()
    val sessionList by orchestratorVM.sessionListFlow.collectAsStateWithLifecycle()

    // Predictive back: only the root destinations handle it. Sheets handle their own.
    // The default NavController predictive back behavior is enabled by the backDispatcher
    // set up in MainActivity (setContent { ... } → NavHost enables it automatically on
    // Android 14+ when androidx.activity:activity-compose >= 1.9.0 is on the classpath).

    // Sessions nav-bar item: badge count = sum of cross-session pending requests.
    val sessionsBadgeCount by remember {
        derivedStateOf {
            val currentId = currentRoute
            sessionList.pendingQuestions.count { it.sessionId != currentId } +
                sessionList.pendingPermissions.count { it.sessionId != currentId }
        }
    }

    // Workspace nav-bar item: badge = total changed files across all sessions.
    val workspaceBadgeCount by remember {
        derivedStateOf {
            sessionList.sessionDiffs.values.sumOf { it.size }
        }
    }

    val suiteState = rememberNavigationSuiteScaffoldState()

    NavigationSuiteScaffold(
        state = suiteState,
        // We pick the layout type from the LocalWindowSizeClass — the default heuristic
        // matches our need (Compact=NavigationBar, Medium/Expanded=Rail) but we may
        // need to override Expanded to NavigationSuiteType.NavigationRail for
        // list-detail within routes (the list-detail is built INSIDE the route
        // composable, not by the suite, so the rail is the right shell).
        layoutType = NavigationSuiteType.NavigationBar,
        navigationSuiteItems = {
            NavRoute.topLevel.forEach { dest ->
                item(
                    selected = currentRoute == dest.route,
                    onClick = {
                        nav.navigate(dest.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) },
                    badge = {
                        when (dest) {
                            NavRoute.Sessions -> if (sessionsBadgeCount > 0)
                                Badge { Text(sessionsBadgeCount.coerceAtMost(99).toString()) }
                            NavRoute.Workspace -> if (workspaceBadgeCount > 0)
                                Badge { Text(workspaceBadgeCount.coerceAtMost(99).toString()) }
                            else -> Unit
                        }
                    }
                )
            }
        }
    ) {
        NavHost(
            navController = nav,
            startDestination = NavRoute.Chat.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(NavRoute.Chat.route) {
                ChatScaffold(
                    chatVM = hiltViewModel<ChatViewModel>(),
                    composerVM = hiltViewModel<ComposerViewModel>(),
                    connectionVM = hiltViewModel<ConnectionViewModel>(),
                    sessionVM = hiltViewModel<SessionViewModel>(),
                    hostVM = hiltViewModel<HostViewModel>(),
                    orchestratorVM = orchestratorVM,
                    onOpenSessionPicker = { /* shown in ChatScaffold; sheet state lives there */ },
                    onOpenContextSelector = { /* sheet state lives in ChatScaffold */ },
                )
            }
            composable(NavRoute.Sessions.route) {
                val filesVM: cn.vectory.ocdroid.ui.files.FilesViewModel = hiltViewModel()
                SessionsScreen(
                    viewModel = hiltViewModel(),
                    composerVM = hiltViewModel(),
                    orchestratorVM = orchestratorVM,
                    settingsVM = hiltViewModel(),
                    repository = filesVM.repository,
                    onSwitchToChat = { nav.navigate(NavRoute.Chat.route) { launchSingleTop = true } }
                )
            }
            composable(NavRoute.Workspace.route) {
                WorkspaceScaffold(
                    orchestratorVM = orchestratorVM,
                    onOpenSession = { id -> orchestratorVM.openSessionFromDeepLink(id) }
                )
            }
            composable(NavRoute.Settings.route) {
                SettingsScaffold(
                    hostVM = hiltViewModel(),
                    settingsVM = hiltViewModel(),
                    connectionVM = hiltViewModel(),
                    composerVM = hiltViewModel(),
                    orchestratorVM = orchestratorVM,
                    onBack = { nav.popBackStack() }
                )
            }
        }
    }
}
```

### D.2  `ChatScaffold` — TopAppBar + transcript + composer

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.2
package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.ui.*

/**
 * Replaces ChatScreen.kt:96-884. Binds the same SliceFlows the old screen read.
 * Differences:
 *   - No AnimatedContent of three pages (the shell handles nav).
 *   - No HorizontalPager between sessions (the session picker handles switch).
 *   - No file-browser overlay (the Workspace route handles it).
 *   - Top bar carries a context chip (D.5) instead of the workdir-basename subtitle.
 *   - Status slot is a single composable (D.2.1) with a strict priority rule.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScaffold(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    connectionVM: ConnectionViewModel,
    sessionVM: SessionViewModel,
    hostVM: HostViewModel,
    orchestratorVM: OrchestratorViewModel,
    onOpenSessionPicker: () -> Unit,
    onOpenContextSelector: () -> Unit,
) {
    val chat by chatVM.chatFlow.collectAsStateWithLifecycle()
    val sessionList by chatVM.sessionListFlow.collectAsStateWithLifecycle()
    val composer by composerVM.composerFlow.collectAsStateWithLifecycle()
    val connection by connectionVM.connectionFlow.collectAsStateWithLifecycle()
    val settings by orchestratorVM.settingsFlow.collectAsStateWithLifecycle()
    val host by orchestratorVM.hostFlow.collectAsStateWithLifecycle()

    val currentSession = remember(chat.currentSessionId, sessionList.sessions) {
        sessionList.sessions.find { it.id == chat.currentSessionId }
    }
    val currentSessionStatus = remember(chat.currentSessionId, sessionList.sessionStatuses) {
        sessionList.sessionStatuses[chat.currentSessionId]
    }

    var showSessionPicker by rememberSaveable { mutableStateOf(false) }
    var showContextSelector by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            // Single TopAppBar — no tab strip, no workdir subtitle (moved to context chip).
            ChatTopBarRedesign(
                title = currentSession?.displayName ?: "New conversation",
                contextLabel = contextChipLabel(currentSession?.directory, host),
                onOpenSessionPicker = { showSessionPicker = true },
                onOpenContextSelector = { showContextSelector = true },
                onOpenOverflow = { showOverflow = true },
                parentTitle = currentSession?.parentId?.let { pid ->
                    sessionList.sessions.firstOrNull { it.id == pid }?.displayName
                },
            )
        },
        // No bottomBar: the composer is a content-level element (it must respect
        // imePadding and is read in the transcript area's Column).
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Status slot — one composable, priority-ordered.
            val pendingQuestion = sessionList.pendingQuestions
                .firstOrNull { it.sessionId == chat.currentSessionId }
            val pendingPermission = sessionList.pendingPermissions
                .firstOrNull { it.sessionId == chat.currentSessionId }
            val isRunning = currentSessionStatus?.let { it.isBusy || it.isRetry } == true ||
                chat.currentSessionId?.let { it in composer.sendingSessionIds } == true
            StatusSlot(
                permission = pendingPermission,
                question = pendingQuestion,
                isRunning = isRunning,
                isCompacting = chat.isCompacting,
                compactStartedAt = chat.compactStartedAt,
                isConnecting = connection.isConnecting && !connection.isConnected,
                onRespondPermission = { response ->
                    pendingPermission?.let { p ->
                        orchestratorVM.respondPermission(p.sessionId, p.id, response)
                    }
                },
                onAbort = chatVM::abortSession,
                currentActivityText = currentSessionActivity(
                    sessionId = chat.currentSessionId,
                    status = currentSessionStatus,
                    messages = visibleMessages(chat.messages, currentSession),
                    partsByMessage = chat.partsByMessage,
                    streamingReasoningPart = chat.streamingReasoningPart,
                    streamingPartTexts = chat.streamingPartTexts,
                )?.text,
            )

            // Transcript (full width — no Surface wrapper on compact; the
            // old "card on wide" wrapping is dropped: WindowSizeClass branching
            // is a shell decision, not a screen-local one).
            ChatMessageList(
                chatVM = chatVM,
                composerVM = composerVM,
                sessionVM = sessionVM,
                onFileClick = { path -> /* route to workspace/files */ },
                onOpenChangesTab = { /* route to workspace/changes */ },
            )

            // Composer (D.3).
            if (chat.currentSessionId != null || composer.draftWorkdir != null) {
                Composer(
                    chatVM = chatVM,
                    composerVM = composerVM,
                    orchestratorVM = orchestratorVM,
                    isBusy = isRunning || chat.isCompacting,
                    questionPending = pendingQuestion != null,
                    agentName = settings.selectedAgentName,
                    agents = settings.agents,
                    currentModelName = chat.currentModel?.modelId,
                    providers = settings.providers,
                    disabledModels = settings.disabledModels,
                )
            }
        }
    }

    if (showSessionPicker) {
        SessionPickerSheet(
            sessions = sessionList.sessions,
            sessionStatuses = sessionList.sessionStatuses,
            currentSessionId = chat.currentSessionId,
            unreadSessions = sessionList.unreadSessions,
            onSelect = { id ->
                showSessionPicker = false
                sessionVM.selectSession(id)
            },
            onNewSession = {
                showSessionPicker = false
                // Use the active workdir; if none, prompt via the context selector first.
                composer.draftWorkdir?.let { sessionVM.createSessionInWorkdir(it) }
            },
            onDismiss = { showSessionPicker = false }
        )
    }

    if (showContextSelector) {
        ContextSelectorSheet(
            hostProfiles = host.hostProfiles,
            currentHostProfileId = host.currentHostProfileId,
            currentWorkdir = currentSession?.directory ?: composer.draftWorkdir,
            onSwitchHost = { id -> hostVM.selectHostProfile(id) },
            onSwitchWorkdir = { dir ->
                // Setting draft workdir resets the current session scope (report P5-2).
                composerVM.setDraftWorkdir(dir)
            },
            onManageHosts = {
                showContextSelector = false
                // Navigate to settings/hosts in the same NavHost.
            },
            onConnectProject = {
                // Opens the existing DirectoryPickerSheet (DirectoryPicker.kt:67-289).
            },
            onDismiss = { showContextSelector = false }
        )
    }

    if (showOverflow) {
        DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { showOverflow = false; /* open rename dialog */ }
            )
            DropdownMenuItem(
                text = { Text("Archive") },
                onClick = { showOverflow = false; currentSession?.id?.let(sessionVM::archiveSession) }
            )
            DropdownMenuItem(
                text = { Text("Copy link") },
                onClick = { showOverflow = false; /* share session id */ }
            )
        }
    }

    // Back: only intercept for the exit-confirm double-tap (current ChatScreen.kt:227-235).
    // The shell-level BackHandler handles route navigation.
    BackHandler(enabled = chat.currentSessionId != null && currentSession?.parentId == null) {
        // Same 1s arming + snackbar pattern. Hoisted into a small util in a follow-up.
    }
}

/** Pure: derives the context chip label "Host → Workdir". */
private fun contextChipLabel(workdir: String?, host: HostState): String {
    val hostName = host.hostProfiles.firstOrNull { it.id == host.currentHostProfileId }?.name
        ?: "No host"
    val workdirBase = workdir?.split("/")?.filter { it.isNotEmpty() }?.lastOrNull()
        ?: "—"
    return "$hostName → $workdirBase"
}

/**
 * The single status slot. Replaces ChatScreen.kt:687-770 + ChatScreen.kt:816-834.
 * Only one of the supplied values is rendered (priority 1 > 2 > 3 > 4 > 5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatusSlot(
    permission: cn.vectory.ocdroid.data.model.PermissionRequest?,
    question: cn.vectory.ocdroid.data.model.QuestionRequest?,
    isRunning: Boolean,
    isCompacting: Boolean,
    compactStartedAt: Long,
    isConnecting: Boolean,
    onRespondPermission: (cn.vectory.ocdroid.data.model.PermissionResponse) -> Unit,
    onAbort: () -> Unit,
    currentActivityText: String?,
) {
    val prioritySurface: @Composable () -> Unit = when {
        permission != null -> {
            { ChatPermissionCard(permission = permission, onRespond = onRespondPermission) }
        }
        question != null -> {
            { /* QuestionCardView lives as before, anchored above the composer */ }
        }
        /* retry/error priority: ChatScreen passes a sessionStatus here; simplified. */
        isCompacting -> { { ThinkingCapsule(text = "Compacting…", startedAtMillis = compactStartedAt.takeIf { it > 0 }, onAbort = {}, showAbort = false) } }
        isRunning && currentActivityText != null -> { { ThinkingCapsule(text = currentActivityText, startedAtMillis = null, onAbort = onAbort) } }
        isConnecting -> { { ThinkingCapsule(text = "Connecting…", startedAtMillis = null, onAbort = {}, showAbort = false) } }
        else -> { {} }
    }
    AnimatedContent(
        targetState = prioritySurface,
        transitionSpec = {
            (fadeIn(animationSpec = androidx.compose.animation.core.tween(150)) +
                androidx.compose.animation.slideInVertically(animationSpec = androidx.compose.animation.core.tween(150)) { -it / 4 })
                .togetherWith(
                    fadeOut(animationSpec = androidx.compose.animation.core.tween(120)) +
                        androidx.compose.animation.slideOutVertically(animationSpec = androidx.compose.animation.core.tween(120)) { -it / 4 }
                )
        },
        label = "statusSlot"
    ) { surface ->
        surface()
    }
}
```

### D.3  `Composer` — Agent / Model chips + Add-menu sheet

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.3
package cn.vectory.ocdroid.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import cn.vectory.ocdroid.data.model.AgentInfo
import cn.vectory.ocdroid.data.model.ProvidersResponse
import cn.vectory.ocdroid.ui.*

/**
 * Replaces ChatInputBar.kt:57-293. Differences:
 *   - Two chips above the input row (Agent / Model).
 *   - `+` opens a ModalBottomSheet (Photos / File ref / Commands).
 *   - The primary send/stop is an M3 IconButton (48dp).
 *   - Slash-command autocomplete continues to work inline (CommandSuggestionsPanel
 *     stays; the modal Commands entry simply calls onTextChange("/")).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    chatVM: ChatViewModel,
    composerVM: ComposerViewModel,
    orchestratorVM: OrchestratorViewModel,
    isBusy: Boolean,
    questionPending: Boolean,
    agentName: String?,
    agents: List<AgentInfo>,
    currentModelName: String?,
    providers: ProvidersResponse?,
    disabledModels: Set<String>,
) {
    val composerState by composerVM.composerFlow.collectAsStateWithLifecycle()
    val settingsState by composerVM.settingsFlow.collectAsStateWithLifecycle()
    val text = composerState.inputText
    val imageAttachments = composerState.imageAttachments
    val availableCommands = settingsState.availableCommands
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        scope.launch { composerVM.addImageAttachments(loadImageAttachments(context, uris)) }
    }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().imePadding(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RectangleShape,
        shadowElevation = 2.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // Slash-command suggestions — keep existing pattern (ChatInputBar.kt:301-345).
            if (text.startsWith("/") && !text.contains(' ')) {
                val matches = availableCommands.filter {
                    it.name.lowercase().startsWith(text.removePrefix("/").lowercase()) &&
                        it.name.lowercase() != text.removePrefix("/").lowercase()
                }
                if (matches.isNotEmpty()) {
                    CommandSuggestionsPanel(commands = matches, onPick = { name ->
                        composerVM.setInputText("/$name ")
                    })
                }
            }

            if (imageAttachments.isNotEmpty()) {
                ImageAttachmentStrip(
                    attachments = imageAttachments,
                    onRemoveImage = composerVM::removeImageAttachment,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // File-reference chips (scheme A — text part, no protocol change).
            // For now render only if the input text contains "File: <path>" tokens;
            // a richer chip model is added in (F).
            FileReferenceChipStrip(
                inputText = text,
                onRemove = { /* rewrite inputText removing the token */ }
            )

            // Chip row: [Agent chip] [Model chip].  Thumb-zone top of composer.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = { showAgentPicker = true },
                    label = { Text(agentName ?: "Default agent") },
                    leadingIcon = {
                        Icon(Icons.Default.SmartToy, contentDescription = null,
                            modifier = Modifier.size(18.dp))
                    },
                    enabled = !questionPending,
                )
                AssistChip(
                    onClick = { showModelPicker = true },
                    label = { Text(currentModelName ?: "Pick model") },
                    leadingIcon = {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Memory,
                            contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    enabled = !questionPending,
                )
            }

            // Editor row: [+] [input weight=1f] [send/stop]
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { showAdd = true },
                    enabled = !questionPending,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            if (questionPending) "Answer the question first…"
                            else "Message the agent…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = composerVM::setInputText,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp, max = 120.dp),
                        enabled = !questionPending,
                        textStyle = LocalTextStyle.current.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 3
                    )
                }
                val canSend = (text.isNotBlank() || imageAttachments.isNotEmpty()) && !questionPending
                val canStop = isBusy && !canSend
                IconButton(
                    onClick = {
                        if (canStop) showStopConfirm = true
                        else handleComposerSend(
                            text = text,
                            availableCommands = availableCommands,
                            allowCommand = !isBusy,
                            onSendMessage = chatVM::sendMessage,
                            onExecuteCommand = orchestratorVM::executeCommand,
                            onCompact = chatVM::compactSession
                        )
                    },
                    enabled = (canStop || canSend) && !questionPending,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        if (canStop) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (canStop) "Stop" else "Send",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if ((canStop || canSend) && !questionPending) 1f else 0.5f)
                    )
                }
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                ListItem(
                    headlineContent = { Text("Photos") },
                    leadingContent = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAdd = false
                        imagePicker.launch("image/*")
                    }
                )
                ListItem(
                    headlineContent = { Text("Reference workspace file") },
                    leadingContent = { Icon(Icons.Default.AttachFile, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAdd = false
                        // Route to workspace/files with a callback that returns the chosen path.
                        // (Implementation: emit a one-shot "pick file for composer" intent;
                        // see (F) for the state addition.)
                    }
                )
                ListItem(
                    headlineContent = { Text("Commands") },
                    supportingContent = { Text("Insert a slash command") },
                    leadingContent = { Icon(Icons.Default.Terminal, contentDescription = null) },
                    modifier = Modifier.clickable {
                        showAdd = false
                        composerVM.setInputText("/")
                    }
                )
            }
        }
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = agents.filter { it.isVisible },
            selectedAgentName = agentName,
            onPick = { name -> composerVM.selectAgent(name); showAgentPicker = false },
            onDismiss = { showAgentPicker = false }
        )
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = providers,
            disabledModels = disabledModels,
            currentModel = /* resolved elsewhere */ null,
            onSwitchModel = { providerId, modelId ->
                composerVM.switchSessionModel(providerId, modelId)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop the agent?") },
            text = { Text("The current turn will be interrupted.") },
            confirmButton = {
                TextButton(onClick = { chatVM.abortSession(); showStopConfirm = false }) {
                    Text("Stop", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

/** Compose placeholders — pure-UI helpers extracted from the existing code. */
@Composable private fun FileReferenceChipStrip(inputText: String, onRemove: (String) -> Unit) { /* … */ }
@Composable private fun AgentPickerSheet(agents: List<AgentInfo>, selectedAgentName: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) { /* … */ }
@Composable private fun ModelPickerSheet(providers: ProvidersResponse?, disabledModels: Set<String>, currentModel: cn.vectory.ocdroid.data.model.Message.ModelInfo?, onSwitchModel: (String, String) -> Unit, onDismiss: () -> Unit) { /* … */ }
```

### D.4  `SessionPickerSheet`

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.4
package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.data.model.Session
import cn.vectory.ocdroid.data.model.SessionStatus
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * Replaces the browser-style tab strip in ChatSessionTabStrip.kt:128-162 and
 * the second-row SessionTabStrip rendering in ChatTopBar.kt:449-462. Also
 * replaces the long-press-driven archive gesture (SessionsScreen.kt:208)
 * with an explicit overflow menu — per P4-4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPickerSheet(
    sessions: List<Session>,
    sessionStatuses: Map<String, SessionStatus>,
    currentSessionId: String?,
    unreadSessions: Set<String>,
    onSelect: (String) -> Unit,
    onNewSession: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var overflowFor by remember { mutableStateOf<String?>(null) }

    val recent = remember(sessions, query) {
        sessions.filter { it.parentId == null && !it.isArchived }
            .filter {
                query.isBlank() ||
                    it.displayName.contains(query, ignoreCase = true) ||
                    it.directory.contains(query, ignoreCase = true)
            }
            .sortedByDescending { it.time?.updated ?: 0L }
            .take(10)
    }
    val needsAction = remember(sessions, unreadSessions, sessionStatuses) {
        sessions.filter { it.parentId == null && !it.isArchived }
            .filter { it.id in unreadSessions || sessionStatuses[it.id]?.isRetry == true }
    }
    val byWorkdir = remember(sessions) {
        sessions.filter { it.parentId == null && !it.isArchived }
            .groupBy { it.directory }
            .toSortedMap()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            // Header.
            Text(
                "Sessions",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            // Search.
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { /* no-op — filter is live */ },
                        expanded = false,
                        onExpandedChange = { },
                        placeholder = { Text("Search by name or workdir") }
                    )
                },
                expanded = false,
                onExpandedChange = { },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) { /* no suggestion content — query filters the list directly */ }

            Spacer(Modifier.height(8.dp))

            // Lists.
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                if (needsAction.isNotEmpty()) {
                    item("hdr_needs") { SectionHeader("Needs action") }
                    items(needsAction, key = { it.id }) { s ->
                        SessionRow(
                            session = s, isSelected = s.id == currentSessionId,
                            status = sessionStatuses[s.id], isUnread = s.id in unreadSessions,
                            onClick = { onSelect(s.id) }, onOverflow = { overflowFor = s.id }
                        )
                    }
                    item("div_needs") { HorizontalDivider() }
                }
                item("hdr_recent") { SectionHeader("Recent") }
                items(recent, key = { it.id }) { s ->
                    SessionRow(
                        session = s, isSelected = s.id == currentSessionId,
                        status = sessionStatuses[s.id], isUnread = s.id in unreadSessions,
                        onClick = { onSelect(s.id) }, onOverflow = { overflowFor = s.id }
                    )
                }
                item("hdr_workdirs") { SectionHeader("By workdir") }
                byWorkdir.forEach { (workdir, list) ->
                    item("workdir_${workdir}") {
                        Text(
                            workdir.split("/").lastOrNull() ?: workdir,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    items(list, key = { it.id }) { s ->
                        SessionRow(
                            session = s, isSelected = s.id == currentSessionId,
                            status = sessionStatuses[s.id], isUnread = s.id in unreadSessions,
                            onClick = { onSelect(s.id) }, onOverflow = { overflowFor = s.id }
                        )
                    }
                }
            }

            // Footer: New session.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                ExtendedFloatingActionButton(
                    onClick = onNewSession,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("New session") }
                )
            }
        }
    }

    // Per-row overflow menu (replaces the long-press archive gesture).
    val overflowSession = overflowFor?.let { id -> sessions.firstOrNull { it.id == id } }
    if (overflowSession != null) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { overflowFor = null }
        ) {
            DropdownMenuItem(text = { Text("Rename") }, onClick = { overflowFor = null; /* … */ })
            DropdownMenuItem(
                text = { Text("Archive", color = MaterialTheme.colorScheme.error) },
                onClick = { overflowFor = null; /* sessionVM.archiveSession(...) */ }
            )
            DropdownMenuItem(text = { Text("Copy link") }, onClick = { overflowFor = null; /* … */ })
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionRow(
    session: Session,
    isSelected: Boolean,
    status: SessionStatus?,
    isUnread: Boolean,
    onClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val tone = workdirTone(session.directory)
    ListItem(
        headlineContent = {
            Text(
                session.displayName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                "${session.directory.split("/").lastOrNull() ?: ""}  •  ${formatTime(session.time?.updated ?: 0L)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier.size(10.dp).clip(CircleShape).background(tone)
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status?.isRetry == true) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                }
                if (isUnread) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                IconButton(onClick = onOverflow) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick)
            .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface)
    )
}
```

### D.5  `ContextSelectorSheet` + `ChatTopBarRedesign` (context chip)

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.5
package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.HostProfile

/**
 * Replaces the DNS icon + ServerManagementDialog entry (ChatTopBar.kt:386-428)
 * and the workdir picker buried in SessionsScreen.kt:218-228, 343-374. One
 * surface; one mental model. Report P5-2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSelectorSheet(
    hostProfiles: List<HostProfile>,
    currentHostProfileId: String?,
    currentWorkdir: String?,
    onSwitchHost: (String) -> Unit,
    onSwitchWorkdir: (String) -> Unit,
    onManageHosts: () -> Unit,
    onConnectProject: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val currentHost = hostProfiles.firstOrNull { it.id == currentHostProfileId }
    val recentWorkdirs = remember(currentHost) { currentHost?.recentWorkdirs.orEmpty() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            // Header — current context echo.
            ListItem(
                headlineContent = {
                    Text(
                        currentHost?.name ?: "No host",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                supportingContent = {
                    Text(
                        currentWorkdir?.split("/")?.filter { it.isNotEmpty() }?.lastOrNull() ?: "No workdir",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                leadingContent = { Icon(Icons.Default.Dns, contentDescription = null) }
            )
            HorizontalDivider()

            // Hosts section.
            Text("Host",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            hostProfiles.forEach { host ->
                ListItem(
                    headlineContent = { Text(host.name) },
                    supportingContent = {
                        Text(host.serverUrl, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.Dns, contentDescription = null) },
                    trailingContent = {
                        RadioButton(
                            selected = host.id == currentHostProfileId,
                            onClick = { onSwitchHost(host.id) }
                        )
                    },
                    modifier = Modifier.clickable { onSwitchHost(host.id) }
                )
            }
            ListItem(
                headlineContent = { Text("Manage hosts") },
                leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { onManageHosts() }
            )
            HorizontalDivider()

            // Workdirs section.
            Text("Workdir",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            recentWorkdirs.forEach { wd ->
                ListItem(
                    headlineContent = {
                        Text(wd.split("/").filter { it.isNotEmpty() }.lastOrNull() ?: wd,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(wd, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                    trailingContent = {
                        RadioButton(
                            selected = wd == currentWorkdir,
                            onClick = { onSwitchWorkdir(wd) }
                        )
                    },
                    modifier = Modifier.clickable { onSwitchWorkdir(wd) }
                )
            }
            ListItem(
                headlineContent = { Text("Connect project…") },
                leadingContent = { Icon(Icons.Default.Add, contentDescription = null) },
                modifier = Modifier.clickable { onConnectProject() }
            )
        }
    }
}

/**
 * The new TopAppBar. Replaces ChatTopBar.kt:228-463.
 * - Left: session-history `IconButton` (Menu icon) → SessionPickerSheet.
 * - Center: title + parent breadcrumb (existing logic) — no workdir subtitle.
 * - Right: context `AssistChip` (Host → Workdir) → ContextSelectorSheet, plus overflow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatTopBarRedesign(
    title: String,
    contextLabel: String,
    onOpenSessionPicker: () -> Unit,
    onOpenContextSelector: () -> Unit,
    onOpenOverflow: () -> Unit,
    parentTitle: String?,
) {
    TopAppBar(
        windowInsets = TopAppBarDefaults.windowInsets,
        navigationIcon = {
            IconButton(onClick = onOpenSessionPicker) {
                Icon(
                    Icons.AutoMirrored.Filled.Menu,
                    contentDescription = "Sessions"
                )
            }
        },
        title = {
            // Parent breadcrumb + title (unchanged logic from ChatTopBar.kt:249-286).
            if (parentTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(parentTitle, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(" / ", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(title, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(title, style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        actions = {
            // The context chip. 48dp tall (default chip).
            AssistChip(
                onClick = onOpenContextSelector,
                label = { Text(contextLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null,
                    modifier = Modifier.size(16.dp)) },
            )
            IconButton(onClick = onOpenOverflow) {
                Icon(Icons.Default.MoreVert, contentDescription = "Conversation actions")
            }
        }
    )
}
```

### D.6  `MessageCard`

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.6
package cn.vectory.ocdroid.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.data.model.Message
import cn.vectory.ocdroid.ui.chat.workdirTone
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * The new per-message container. Replaces the "no speaker label, no avatar"
 * text-wall rendering in MessageRow.kt:82-100 (the comment "user's blue left
 * bar vs the assistant's container-less reply already make it clear who's
 * speaking, so an extra blue label is redundant" is the V2/V8 problem this
 * card fixes).
 *
 * Binds to existing fields: message.isUser, message.agent, message.time,
 * message.resolvedModel, message.error. The Part content (text / tool /
 * patch / reasoning / sub-agent) is rendered by the existing per-part
 * composables (PartView at ChatMessageRow.kt:278-340) inside the card body —
 * we only replace the OUTER framing, not the per-part rendering.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageCard(
    message: Message,
    onCopy: () -> Unit,
    onEditAndRerun: () -> Unit,
    onFork: () -> Unit,
    onRevert: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    val isUser = message.isUser
    // §issue-4: task-completion messages render as assistant (left aligned).
    val isTaskCompletion = isUser && message.parts.any { p ->
        p.isText && (p.text ?: "").contains("<task") &&
            parseTaskXml(p.text)?.state?.let {
                it.equals("completed", true) || it.equals("error", true)
            } == true
    }
    val role: MessageRole = when {
        isTaskCompletion -> MessageRole.Agent
        isUser -> MessageRole.User
        message.role == "system" -> MessageRole.System
        else -> MessageRole.Agent
    }

    val (surfaceColor, onSurface, accent) = when (role) {
        MessageRole.Agent -> Triple(
            MaterialTheme.colorScheme.surfaceContainer,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.primary
        )
        MessageRole.User -> Triple(
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurface,
            MaterialTheme.colorScheme.tertiary
        )
        MessageRole.System -> Triple(
            MaterialTheme.colorScheme.surfaceContainerLowest,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outline
        )
    }
    val tone = remember(message.agent) { message.agent?.let { workdirTone(it) } }

    var overflowOpen by remember { mutableStateOf(false) }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = surfaceColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar — 3dp, role color (preserves the v0.x "left bar" cue).
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accent)
            )
            Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                // Header row — avatar + sender + time + overflow.
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = surfaceColor),
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                                .background((tone ?: accent).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                when (role) {
                                    MessageRole.Agent -> Icons.Default.SmartToy
                                    MessageRole.User -> Icons.Default.Person
                                    MessageRole.System -> Icons.Default.Info
                                },
                                contentDescription = null,
                                tint = tone ?: accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    },
                    headlineContent = {
                        Text(
                            senderLabel(message, role),
                            style = MaterialTheme.typography.titleSmall,
                            color = onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    supportingContent = {
                        Text(
                            metaLine(message, role),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingContent = {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Message actions")
                        }
                    }
                )
                // The existing per-part content goes here (text/tool/patch/…).
                body()
            }
        }
    }

    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
        DropdownMenuItem(text = { Text("Copy") }, onClick = { overflowOpen = false; onCopy() })
        if (isUser) {
            DropdownMenuItem(
                text = { Text("Edit & rerun from here") },
                onClick = { overflowOpen = false; onEditAndRerun() }
            )
        }
        DropdownMenuItem(text = { Text("Fork from here") }, onClick = { overflowOpen = false; onFork() })
        DropdownMenuItem(
            text = { Text("Revert to this point", color = MaterialTheme.colorScheme.error) },
            onClick = { overflowOpen = false; onRevert() }
        )
    }
}

private enum class MessageRole { Agent, User, System }

private fun senderLabel(message: Message, role: MessageRole): String = when (role) {
    MessageRole.Agent -> message.agent ?: "Assistant"
    MessageRole.User -> "You"
    MessageRole.System -> "System"
}

private fun metaLine(message: Message, role: MessageRole): String {
    val time = message.time?.completed ?: message.time?.created
    val hhmm = time?.let { /* formatHm() — existing helper */ "" }
    return when (role) {
        MessageRole.Agent -> listOfNotNull(message.resolvedModel?.modelId, hhmm).joinToString(" · ")
        MessageRole.User -> hhmm.orEmpty()
        MessageRole.System -> hhmm.orEmpty()
    }
}
```

### D.7  `WorkspaceScaffold` (new — Files | Changes)

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.7
package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import cn.vectory.ocdroid.ui.OrchestratorViewModel
import cn.vectory.ocdroid.ui.files.FilesPane
import cn.vectory.ocdroid.ui.workspace.ChangesPane
import cn.vectory.ocdroid.ui.workspace.WorkspaceState

/**
 * New destination. Replaces the fileBrowserOpen overlay (MainActivity.kt:399-415)
 * and the vcs section buried in Settings (SettingsScreen.kt:307-322, 379-557).
 *
 * Two sub-tabs (Files / Changes) rendered via PrimaryTabRow. At Expanded width,
 * the local WindowSizeClass triggers a list-detail split (list on the left, file
 * preview / diff detail on the right) — same composable, different layout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScaffold(
    orchestratorVM: OrchestratorViewModel,
    onOpenSession: (String) -> Unit,
) {
    val connection by orchestratorVM.connectionFlow.collectAsStateWithLifecycle()
    val sessionList by orchestratorVM.sessionListFlow.collectAsStateWithLifecycle()
    val workspaceState = remember { WorkspaceState() } // tiny state holder (F.3)

    var tab by rememberSaveable { mutableStateOf(WorkspaceTab.Files) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                WorkspaceTab.values().forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        text = { Text(entry.label) }
                    )
                }
            }
            when (tab) {
                WorkspaceTab.Files -> FilesPane(
                    onFileClick = { /* open preview; list-detail on expanded */ }
                )
                WorkspaceTab.Changes -> ChangesPane(
                    sessionDiffs = sessionList.sessionDiffs,
                    state = workspaceState,
                    onOpenSession = onOpenSession,
                )
            }
        }
    }
}

enum class WorkspaceTab(val label: String) {
    Files("Files"),
    Changes("Changes"),
}
```

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.7b
package cn.vectory.ocdroid.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.vectory.ocdroid.data.model.FileDiff
import cn.vectory.ocdroid.ui.theme.SemanticColors

/**
 * The Changes tab. On compact: list of changed files. Tap a file → slide up a
 * ModalBottomSheet with the unified diff. On expanded: list-detail (list on the
 * left, diff on the right) — built locally by reading the selectedDiffFile
 * field on WorkspaceState (F.3).
 *
 * Replaces the SessionDiffCard pinned at the bottom of the chat transcript
 * (ChatMessageContent.kt:596-606). The chat card becomes a deep link:
 * `navController.navigate("workspace/changes?session=$id")`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangesPane(
    sessionDiffs: Map<String, List<FileDiff>>,
    state: WorkspaceState,
    onOpenSession: (String) -> Unit,
) {
    val flatEntries = remember(sessionDiffs) {
        sessionDiffs.flatMap { (sid, diffs) -> diffs.map { sid to it } }
            .sortedBy { it.second.file }
    }
    val selected = state.selectedDiffFile

    var statusFilter by rememberSaveable { mutableStateOf("All") }
    val statusOptions = listOf("All", "Added", "Modified", "Deleted", "Untracked")

    Column(modifier = Modifier.fillMaxSize()) {
        // Filter row.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            statusOptions.forEach { opt ->
                FilterChip(
                    selected = statusFilter == opt,
                    onClick = { statusFilter = opt },
                    label = { Text(opt) }
                )
            }
        }
        HorizontalDivider()

        // List.
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(flatEntries, key = { (sid, diff) -> "$sid|${diff.file}" }) { (sid, diff) ->
                ListItem(
                    headlineContent = {
                        Text(diff.file.substringAfterLast("/").ifEmpty { diff.file },
                            maxLines = 1)
                    },
                    supportingContent = {
                        Text(diff.file, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                    },
                    leadingContent = {
                        Box(
                            modifier = Modifier.size(10.dp).clip(CircleShape)
                                .background(colorForStatus(diff.status))
                        )
                    },
                    trailingContent = {
                        if (diff.additions > 0) {
                            Text("+${diff.additions}",
                                color = SemanticColors.stateSuccessFg(),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        if (diff.deletions > 0) {
                            Spacer(Modifier.width(4.dp))
                            Text("-${diff.deletions}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.clickable { state.selectedDiffFile = sid to diff }
                )
            }
        }
    }

    if (selected != null) {
        val (sid, diff) = selected
        ModalBottomSheet(onDismissRequest = { state.selectedDiffFile = null }) {
            // Unified diff body — reuses the diff helpers from SessionDiffCard.kt
            // (DiffPatchView / buildAnnotatedDiff). The class is internal but the
            // helpers are simple to lift during (G.2) implementation.
            DiffSheetBody(
                sessionId = sid,
                diff = diff,
                onOpenSession = onOpenSession
            )
        }
    }
}

@Composable
private fun colorForStatus(status: String?) = when (status?.lowercase()) {
    "added" -> SemanticColors.addedFile
    "deleted" -> SemanticColors.deletedFile
    "modified" -> SemanticColors.modifiedFile
    else -> SemanticColors.untrackedFile
}
```

### D.8  `SettingsScaffold` (slim — global only)

```kotlin
// File: docs/redesign-mobile-compose-scheme.md — D.8
package cn.vectory.ocdroid.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cn.vectory.ocdroid.ui.*

/**
 * Replaces SettingsScreen.kt:73-305. Differences:
 *   - global-only (no VCS, no workdir section — those moved to Workspace).
 *   - top-level list of section ListItems → nested sub-routes (same NavHost).
 *   - per-section sub-routes own their own Scaffold (not boolean-branched).
 */
@Composable
fun SettingsScaffold(
    hostVM: HostViewModel,
    settingsVM: SettingsViewModel,
    connectionVM: ConnectionViewModel,
    composerVM: ComposerViewModel,
    orchestratorVM: OrchestratorViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            sections.forEach { sec ->
                item(sec.route) {
                    ListItem(
                        headlineContent = { Text(sec.title) },
                        supportingContent = { Text(sec.subtitle) },
                        leadingContent = { Icon(sec.icon, contentDescription = null) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
                        modifier = Modifier.clickable { /* navController.navigate(sec.route) */ }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

private data class SettingsSection(
    val route: String, val title: String, val subtitle: String, val icon: ImageVector
)

private val sections = listOf(
    SettingsSection("settings/hosts",       "Hosts & security", "Add, switch, or remove OpenCode hosts", Icons.Default.Dns),
    SettingsSection("settings/appearance",  "Appearance",        "Theme, font, zoom",                       Icons.Default.Palette),
    SettingsSection("settings/models",      "Models",            "Providers and per-host model visibility", Icons.Default.Memory),
    SettingsSection("settings/notifications", "Notifications",  "Alerts for completions and errors",        Icons.Default.Notifications),
    SettingsSection("settings/storage",     "Storage",           "Cache and local data",                    Icons.Default.Storage),
    SettingsSection("settings/about",       "About",             "Version, licenses, diagnostics",          Icons.Default.Info),
)
```

---

## (E) Interaction specs

### E.1  Slash-command input

- User types `/` in the composer → the existing `CommandSuggestionsPanel` (`ChatInputBar.kt:301-345`) appears above the row, filtered to names extending the typed token. Pick a row → input becomes `/<name> ` (trailing space, so the user can append args). This is unchanged from today.
- The Add-menu's "Commands" entry simply calls `composerVM.setInputText("/")` — which opens the same inline panel.
- The picker is also a sheet (D.3) on the composer chips row, listing the same set with descriptions. Both paths share the same data (`settingsState.availableCommands`).
- Server-side commands (slash) and client-side `+ Add` commands are NOT merged. The `+` only handles attach / file-ref / slash-insert. Real "command" execution still routes through `orchestratorVM.executeCommand` (`OrchestratorViewModel:85-...`).

### E.2  `+` Add-menu (D.3)

- Tap `+` → `ModalBottomSheet` with three rows:
  1. **Photos** — `imagePicker.launch("image/*")` (same `ActivityResultContracts.GetMultipleContents()` as today; same `composerVM.addImageAttachments` downstream). No new code path.
  2. **Reference workspace file** — emits a "pick file for composer" one-shot intent (see F.2) that navigates to `workspace/files` with a callback. The user picks a file; on return, the composer receives a `ComposerAddAction.InsertFileRef(path)` that adds a chip + inserts `File: <path>` text-part.
  3. **Commands** — `composerVM.setInputText("/")` to trigger the slash panel.

### E.3  Context switching (Host / Workdir) — reset & restore

Per report P5-2, host/workdir changes must be **visible and predictable**. The rules:

- **Switching host** cancels the in-progress conversation scope (resets composer draft, abandons unsent text, clears the active session if the session was host-scoped). The user is dropped onto the empty Composer with the new host's first recent workdir (or "no workdir" + the context chip pointing to the picker).
- **Switching workdir** within the same host preserves the active session only if that session was already on this workdir; otherwise we **materialize a draft** on the new workdir (using `composerVM.setDraftWorkdir` and `sessionVM.createSessionInWorkdir` only on first send). The user is told: "Switched to <basename> — start a new session or pick one from Sessions."
- **Switching workdir does NOT** auto-send the in-flight composer text. The composer text is preserved (in `composerFlow.inputText` — already persistent) and the draft workdir moves alongside it. If the user explicitly hits Send, a new session materializes on the new workdir.
- **Sessions picker** switches the session within the same workdir freely; switching to a session on a different workdir triggers the same workdir-switch flow as above (and updates the context chip).

These rules are enforced at the controller layer in a follow-up (A5-2/A5-3) — the UI surfaces them via the context chip's clear "Host → Workdir" echo + the toast on host/workdir change (already emitted by the host switch controller; we add a similar one on workdir change in F.1).

### E.4  Permission / Question surfacing (single status slot)

- The slot priority rule from C.3 is binding. The visible surface renders at most one of (Permission, Question, Retry/Error, Running/Compacting, Connecting).
- Permissions and questions are **session-scoped** (report P5-7). The current code at `ChatScreen.kt:419-422` already filters pending questions by `chat.currentSessionId`; we extend the same filter to permissions (currently `ChatScreen.kt:827-833` uses `pendingPermissions.firstOrNull()` without the filter — that is the V5 / P5-7 fix).
- Cross-session pending items surface as a `Badge` on the Sessions nav-bar item (computed in D.1 from the un-scoped list).
- A pending permission card MUST display:
  - Host name + workdir basename
  - Session name (so the user knows which session is asking)
  - Tool name + target (filepath / command)
  - Allow once / Allow always / Reject
  - Tap "always" reveals a small `Tooltip`-style disclosure of what "always" means for this permission scope.

### E.5  Swipe / long-press policy (report P4-4)

- **Tap = navigate or select.** This is the canonical action. No hidden gestures.
- **Long-press = open row overflow menu** (rename, archive, fork, copy). Long-press is **never** the only way to reach a destructive action — every long-press action is also reachable from the row's `MoreVert` `IconButton`.
- **Horizontal swipe =** reserved for content that owns the gesture (transcript, list rows within sheets). We **do not** use horizontal swipe for session-switching (the old pager is removed; the new sheet is the way).
- **Pull-to-refresh =** stays in the Sessions list and the Files pane (M3 `PullToRefreshBox` if/when needed; not in this scheme — out of scope).
- **Back gesture =** predictive back, route-aware. Sheets close before route pops.

The visual treatment: every destructive action in an overflow menu is colored with `colorScheme.error` (e.g. "Archive", "Revert", "Disconnect"). The confirmation dialog remains (e.g. existing archive dialog `SessionsScreen.kt:450-473`).

---

## (F) Minimal state / code additions beyond pure UI

The whole point of this scheme is to NOT touch controller/repository/state architecture (report §6). The UI changes require only these small additions. Each one cites the existing piece it extends and where it hooks in.

### F.1  `NavRoute` enum + a `NavController`-backed `navFlow`

Replace the existing `navPage: Int` and `setLastNavPage` (in `OrchestratorViewModel.kt:57-65`) with a typed `NavRoute` enum. The state stays the same shape (an `Int` in `SettingsManager`); only the surface API changes.

```kotlin
// New file: app/src/main/java/cn/vectory/ocdroid/ui/NavRoute.kt
package cn.vectory.ocdroid.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavRoute(val route: String, val label: String, val icon: ImageVector) {
    Chat("chat", "Chat", Icons.Default.Chat),
    Sessions("sessions", "Sessions", Icons.Default.Forum),
    Workspace("workspace", "Workspace", Icons.Default.Folder),
    Settings("settings", "Settings", Icons.Default.Settings);

    companion object {
        val topLevel = entries.toList()
    }
}
```

Hook-in: the `AppShell` `NavHost` consumes this. `OrchestratorViewModel.setLastNavPage` is replaced with `setLastRoute(route: NavRoute)` (1-line, 1:1 mapping to the persisted int via `route.ordinal`). Migration is "old int → new ordinal".

### F.2  One-shot "pick file for composer" intent

The `+ Add → Reference workspace file` flow needs a way to (a) push the workspace/files route and (b) hand the picked path back to the composer. We add a `SharedFlow<UiEvent>` variant — `composerPickFileRequest` — on `OrchestratorViewModel`, distinct from the existing `uiEvents`. The shell collects it and navigates with a callback route arg.

```kotlin
// In OrchestratorViewModel:
private val _composerPickFileRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val composerPickFileRequest: SharedFlow<Unit> = _composerPickFileRequest

fun requestPickFileForComposer() { _composerPickFileRequest.tryEmit(Unit) }
```

Hook-in: composer's `+` menu calls `orchestratorVM.requestPickFileForComposer()`. The `AppShell` collects the flow and navigates to `workspace/files?forComposer=1`. On return, `composerVM.addFileReference(path)` runs.

`addFileReference(path)` is a new method on `ComposerController` that:
- appends an `InputChip` model to a new `ComposerState.fileReferences: List<String>` field (see F.4),
- inserts the literal `File: <path>` text-part into `inputText` (scheme A — zero protocol change).

### F.3  `WorkspaceState` — selected diff file

A tiny state holder for the Workspace destination only. NOT a `SliceFlows` slice — this is local UI state. The `WorkspaceScaffold` constructs it via `remember { WorkspaceState() }`.

```kotlin
// New file: app/src/main/java/cn/vectory/ocdroid/ui/workspace/WorkspaceState.kt
package cn.vectory.ocdroid.ui.workspace

import cn.vectory.ocdroid.data.model.FileDiff
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class WorkspaceState {
    var selectedDiffFile by mutableStateOf<Pair<String, FileDiff>?>(null)
    // (sessionId, FileDiff) — drives the diff sheet.
}
```

### F.4  `ComposerState.fileReferences: List<FileReference>`

A new field on the existing `ComposerState` (`AppStateSlices.kt:136-141`) that holds removable file-reference chips. This is purely additive — no existing writer is touched.

```kotlin
data class ComposerFileReference(val path: String, val id: String = java.util.UUID.randomUUID().toString())

data class ComposerState(
    val inputText: String = "",
    val imageAttachments: List<ComposerImageAttachment> = emptyList(),
    val sendingSessionIds: Set<String> = emptySet(),
    val draftWorkdir: String? = null,
    val fileReferences: List<ComposerFileReference> = emptyList(), // NEW
)
```

Writer: `composerVM.addFileReference(path)` (from F.2) and `composerVM.removeFileReference(id)`. Reader: `Composer` (D.3) for the chip strip. The `sendMessage` orchestration (in `AppCoreOrchestration.kt:267-337`) reads both `inputText` and `fileReferences` to assemble the outgoing parts — same code path, just one extra `PartInput(type=text)` per reference with the `File: <path>` payload (scheme A).

### F.5  `contextChipLabel` derived state

This is a pure derived function in D.2 (`contextChipLabel(workdir, host)`); no state addition. Lives in `ChatScaffold`. Reads `host.hostProfiles`, `host.currentHostProfileId`, `currentSession.directory` / `composer.draftWorkdir` — all already in the slices.

### F.6  No other state changes

- `ChatState` is unchanged.
- `SessionListState` is unchanged. (The `pendingPermissions` filter is applied at the *read* site in `StatusSlot`, not at the *write* site — the underlying list stays canonical.)
- `SettingsState`, `ConnectionState`, `FileState`, `HostState`, `UnreadState`, `NavState` are unchanged.
- The `fileBrowserOpen` boolean on `FileState` (`AppStateSlices.kt:152`) becomes redundant once `workspace/files` is a real route; mark `@Deprecated` in this scheme and remove in a follow-up. (Not removed now to keep the diff bounded.)

---

## (G) Phased implementation order

Each phase is **incrementally shippable**: the app stays working, the surface area shrinks monotonically. The phases mirror report §7.1 / §7.2 and are arranged so that quick wins (P1) ship first and the structural change (P2) lands on a stable base.

### G.0  Foundation (≈1 day)

- Add `NavRoute` enum (F.1). Wire `AppShell` (D.1) with `NavigationSuiteScaffold` + `NavHost`. Keep `navPage: Int` as the persisted source of truth via `setLastRoute` (one-line adapter). The 4 destinations render the EXISTING screens for now — Chat, Sessions, Settings — and `Workspace` is a stub `Scaffold` with a "coming soon" `ListItem`.
- Status: no user-visible change beyond the bottom NavigationBar appearing. Chat looks identical.

### G.1  Quick wins (P4-1, P4-2, P4-3 partial, P5-5, P5-6, P5-7) — ships inside ChatScaffold

Each is a small, scoped change inside the Chat destination only. The shell from G.0 already has ChatScaffold swapped in.

1. **Replace `ChatTopBar` second-row `SessionTabStrip` with the new single `TopAppBar` + session-history icon + context chip** (D.2, D.5). The session-history icon opens a `ModalBottomSheet` (D.4) with the existing `SessionListState` data — but the sheet only shows Recent + By-workdir (no Search yet). This kills the 24dp close targets + the 36dp strip and the page-jump. (P5-3)
2. **Remove `HorizontalPager`** from `ChatScreen.kt:399-414, 615-655` — session switching is now via the sheet. (P5-3)
3. **Replace the custom `+` immediate-image-picker** with the `ModalBottomSheet` Add-menu (D.3) with Photos only for now. (P4-2 partial)
4. **Add the Agent / Model `AssistChip`s above the input row** (D.3). Tap each → `ModalBottomSheet` (no Search yet — copy the existing dialog content). (P4-1)
5. **Single Status Slot** (C.3, D.2.1). Render only one of (Permission, Question, Running, Connecting). The SessionRetryCard, the Compacting capsule, the connecting capsule, the ThinkingCapsule, and the QuestionCardView all funnel through the slot's priority rule. (P5-6)
6. **Filter pending permissions by `chat.currentSessionId`** in the slot's input. (P5-7)
7. **Message row overflow menu** (D.6) — Copy / Edit & rerun / Fork / Revert — with confirmation dialogs. This makes the existing `ChatViewModel.editFromMessage()` / `SessionViewModel.forkSession()` reachable. (P5-5)
8. **Replace the long-press archive gesture** on sessions with the row overflow menu in the sheet. (P4-4)

After G.1 the app is at parity functionally but on a stable, mobile-native shell.

### G.2  Search + context (P3-1, P5-2, P5-4 partial)

1. **Search inside the Session Picker Sheet** (D.4) — `SearchBar` from M3. Same change inside the Agent/Model sheets.
2. **Context Selector Sheet** (D.5) — replaces the DNS icon + buried workdir flow. One sheet, one mental model. (P5-2)
3. **Workspace destination scaffold** (D.7) — `PrimaryTabRow` Files | Changes. Files tab points at the existing `FilesScreen` (relocated to the route, no behavior change).
4. **Changes tab** (D.7b) — list of changed files. Tapping opens a `ModalBottomSheet` with the diff. (P5-4 partial)
5. **Message `N files changed` deep link** — the `SessionDiffCard` at the bottom of the chat (ChatMessageContent.kt:596-606) becomes a single-row `ListItem` that navigates to `workspace/changes?session=<id>`. The detail is no longer embedded in the chat timeline.
6. **Move the VCS section out of Settings** (SettingsScreen.kt:307-322, 379-557). The data class `VcsInfo`/`VcsStatusEntry` readers and the visual code move to `WorkspaceScaffold`'s Changes tab. Settings no longer renders them. (P4-3)

### G.3  Settings cleanup + nav polish (P4-3, P3-1)

1. **Slim Settings** (D.8) — `LazyColumn` of `ListItem` sections, each pushing a sub-route. Sub-routes: `settings/hosts` (the existing `HostProfilesManagerScreen`), `settings/appearance` (existing `AppearanceSection`), `settings/models` (existing `ModelManagementSection`), new sub-routes for Notifications / Storage / About as plain sub-screens.
2. **Add a back arrow to Settings top-app-bar** (it currently has no back on the top-level route — `SettingsScreen.kt:176-180` only renders the back icon when `onBack != null`; the new shell always passes a back).
3. **Predictive back** — set up via the default `PredictiveBackHandler` integration that the navigation-compose 2.7.6 + activity-compose 1.8.2 already wire up when the host activity has `OnBackPressedDispatcher` set (which it does via `enableEdgeToEdge` in `MainActivity.kt:109` — confirm in implementation).
4. **48dp audit** — sweep the codebase for `Modifier.size(<= 40.dp)` on `IconButton` / `clickable` and either remove the explicit size (M3 default = 48dp) or wrap in a 48dp `Box` click target. The known offenders are already removed in earlier phases.

### G.4  Optional follow-ups (deferred)

- **Agent avatar (Live Activity / Dynamic Island)** — `docs/dynamic-island-plan.md` already covers the data side; the UI just plugs into the existing `connectionPhase` slice.
- **Multi-file patch accordions** in Messages get a facelift (lift the diff helpers from `SessionDiffCard.kt` and reuse them inside `MessageCard` to render a "summary → expand" toggle per patch group). Not in this scheme.
- **Tab fade transition for Workspace** (C.5) — already covered by `AnimatedContent` default; cosmetic.

### G.5  Migration safety

- The old `MainActivity.PhoneLayout` (`MainActivity.kt:239-417`) and its `Screen.Chat/Sessions/Settings` enum (`MainActivity.kt:66-78`) live alongside `AppShell` for the duration of G.0–G.1 — the shell is opt-in via a build flag (`useNewShell: Boolean` in `local.properties`), default `true` after G.2 ships.
- `setLastNavPage` (`OrchestratorViewModel.kt:57-65`) is the migration boundary — the new `setLastRoute(NavRoute)` writes the same persisted int (`.ordinal`).
- The `fileBrowserOpen` overlay path (`MainActivity.kt:399-415`) is removed in G.2 step 3, not before — until then `FilesScreen` is reachable both via the overlay and via `workspace/files`.

---

## (H) Report-problem → scheme-solution map

Each row cites the report problem ID and the specific element of this scheme that resolves it.

### H.1  V1–V8 (visual review of the 6 screenshots)

| #    | Problem                                                                 | Resolved by                                                                                  |
|------|--------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| V1   | No bottom navigation (S1–S6)                                            | `NavigationSuiteScaffold` (D.1) with 4 destinations                                           |
| V2   | Agent messages are a flat text wall with no speaker label (S1)         | `MessageCard` with `ListItem` header — avatar + sender + role + time (D.6)                   |
| V3   | Floating panel overlaps the tab strip (S2)                              | `ModalBottomSheet` everywhere; floating `AlertDialog` for stop-confirm only; no overlays near the app bar |
| V4   | Tab strip is non-M3, unreadable truncation (S1, S2)                     | Removed; replaced by `SessionPickerSheet` with `SearchBar` + grouped `ListItem` (D.4)        |
| V5   | Version number in title + unlabeled icons (S1, S4)                      | Title = session display name; connection status is the `AssistChip` in the app bar (D.5); overflow uses labeled `MoreVert` |
| V6   | File browser is a flat list, no search/breadcrumb/back (S6)             | Files becomes a proper destination (D.7) with `SearchBar`, breadcrumb via the TopAppBar title, and the existing back affordance from the shell |
| V7   | Custom pills + custom dot sliders in Settings (S4)                      | Already M3 `SegmentedButton` + `Slider` in `SettingsSections.kt:183-260` — confirmed, kept; wrapped in sub-routes in G.3 |
| V8   | Composer `+` is single-purpose; no file-ref or command entry            | `ModalBottomSheet` Add-menu with Photos / Reference workspace file / Commands (D.3)          |

### H.2  P5 (severity 5) problems

| ID    | Problem                                                                          | Resolved by                                                                                  |
|-------|----------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| P5-1  | No stable mobile navigation hierarchy                                            | `NavigationSuiteScaffold` with 4 typed destinations + `NavHost` (D.1)                       |
| P5-2  | Host / workdir / session contexts are scattered                                  | `ContextSelectorSheet` (D.5); the context `AssistChip` echoes the current scope at a glance  |
| P5-3  | Browser-style tab strip is wrong for mobile                                      | Strip removed; `SessionPickerSheet` with `SearchBar` + grouped list (D.4); `HorizontalPager` removed (G.1 step 2) |
| P5-4  | Diff review is fragmented (timeline tail + Settings VCS)                         | Workspace → Changes (D.7, D.7b) owns diff + VCS; chat card is a deep link                    |
| P5-5  | Revert / fork / edit-from-here exist but are unreachable                         | `MessageCard` row overflow menu (D.6) wires `ChatViewModel.editFromMessage` / `SessionViewModel.forkSession` |
| P5-6  | Multiple top-center overlays compete (thinking / retry / connecting / question) | Single Status Slot with strict priority rule (C.3, D.2.1)                                    |
| P5-7  | Permissions are not session-scoped                                               | `StatusSlot` filters `pendingPermissions` by `chat.currentSessionId` (G.1 step 6); cross-session items become a `Badge` on the Sessions nav-bar item |

### H.3  P4 (severity 4) problems

| ID    | Problem                                                                          | Resolved by                                                                                  |
|-------|----------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| P4-1  | agent / model / context hidden behind the usage ring → 3 clicks                  | Two `AssistChip`s directly above the composer (D.3)                                          |
| P4-2  | `+` is misleading + no file-ref entry                                           | `ModalBottomSheet` Add-menu (D.3) — Photos / Reference file / Commands                       |
| P4-3  | Settings is a junk drawer (workdir + VCS + model + debug + about mixed)         | Slim `SettingsScaffold` (D.8) — 6 sub-routes, all global; VCS moves to Workspace             |
| P4-4  | Inconsistent interaction (long-press / X / double-tap)                          | `tap = primary`, overflow menu = secondary/destructive, long-press removed (G.1 step 8)     |

### H.4  P3 (severity 3) — partially in scope, called out

| ID    | Problem                                                                          | Resolved by                                                                                  |
|-------|----------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| P3-1  | M3 adoption incomplete (no adaptive nav, dot-slider, etc.)                      | M3 `NavigationSuiteScaffold` + `SegmentedButton` + `Slider` + `SearchBar` + `FilterChip` + `ListItem` + `ModalBottomSheet`; the 24/28/40dp offenders are removed in the G.1 sweep |

### H.5  What this scheme does NOT solve (deferred, called out)

- **A5-1 data→ui cycle / A5-2 AppCore locator / A5-3 cross-slice transaction** — report §6. Out of scope.
- **A4-1 SSE pipeline / A4-2 effect bus / A4-3 ConnectionCoordinator** — report §6. Out of scope.
- **A4-4 ChatState / SessionListState god-slices** — out of scope. The scheme READS from them but does not reshape them.
- **A3-1 revert window-leakage bug** (AppStateDerived.kt:111-129) — explicitly called out in report §8.3 as a "revert UI ships ONLY after this is fixed". The `MessageCard` row overflow (D.6) wires the Revert action; **G.1 step 7 must NOT ship before A3-1 is fixed**.
- **A3-2 generation guard / A3-3 scope ownership** — out of scope; no UI surface here is affected.
- **Predictive back (G.3 step 3) requires activity-compose ≥ 1.9.0** for the full animation. Current `activityCompose = "1.8.2"` in `gradle/libs.versions.toml` — bump as part of G.3.
- **Server-side "open file for composer" flow** (F.2) requires a server round-trip if the server is in another workdir. For v1 we restrict file references to the current workdir (matches the scope of `FilesScreen` today). The state addition in F.2 is in place so we can lift the restriction in a follow-up.

---

**End of scheme.** Once the orchestrator signs off on this document, implementation begins at G.0 (F.1 `NavRoute` + `AppShell` skeleton) and proceeds through G.1 → G.3. No existing production file is modified until the corresponding phase lands.
