# ui_driver

A deterministic adb/uiautomator operation layer for the opencode Android
client (`cn.vectory.ocdroid`). It binds the repetitive adb + uiautomator
action sequences into named commands. An AI agent calls a command, reads the
returned UI tree (JSON on stdout), decides, and calls the next command.

Design contract: **every command that changes the UI returns the post-action
UI tree as JSON.** Observe-only commands (`tree`) return the current tree.

## Install / run

```bash
cd ui_driver
uv venv
source .venv/bin/activate
uv pip install -e .            # or: uv pip install pytest  (for tests only)
python -m ui_driver --help
```

## Device selection

The driver targets exactly one device so it never trips
`more than one device/emulator` (a physical phone may be attached). Precedence:

1. `--serial <s>` flag
2. `ANDROID_SERIAL` env var
3. first `emulator-*` reported by `adb devices`

adb binary resolves to `$ANDROID_HOME/platform-tools/adb`, with `ANDROID_HOME`
defaulting to `~/Library/Android/sdk`; override with `--adb <path>`. Falls back
to bare `adb` on PATH if that file is absent.

## Commands

All commands accept `--screenshot <path>` (except `clear-data`) to also save a
PNG and include its path in the JSON. Global flags: `--serial`, `--adb`.

Low-level atomic commands:

| Command | Signature | Effect |
|---|---|---|
| `tree` | `tree [--scroll-find LABEL] [--screenshot P]` | Dump current tree (no action). `--scroll-find` scrolls down until LABEL appears first. |
| `tap-xy` | `tap-xy <x> <y> [--screenshot P]` | Tap a coordinate. |
| `tap-text` | `tap-text <label> [--screenshot P]` | Tap center of first node whose text/content-desc contains `label`. |
| `type-text` | `type-text <text> [--screenshot P]` | Type into the focused field. Spaces are encoded as `%s` and shell metachars escaped. |
| `clear-field` | `clear-field [--screenshot P]` | Clear focused input (MOVE_END then repeated DEL). |
| `key` | `key <back\|home\|enter> [--screenshot P]` | Navigation key. |
| `launch` | `launch [--screenshot P]` | Cold-start `MainActivity`. |
| `clear-data` | `clear-data` | `pm clear` the app to a clean state (returns a small status JSON, not a tree). |
| `scroll-to-text` | `scroll-to-text <label> [--screenshot P]` | Swipe up until `label` is visible or bottom reached. |

High-level deterministic sequences (the main value of this CLI):

| Command | Signature | Sequence |
|---|---|---|
| `configure-server` | `configure-server --url U --username U --password P [--screenshot P]` | BACK (dismiss keyboard) → Settings tab → clear+fill Server URL → fill Username → fill Password → BACK → Test Connection → wait → Save → return tree. |
| `send-prompt` | `send-prompt <text> [--screenshot P]` | Focus chat input → type → re-locate **Send** by content-desc from the live tree → tap it → return tree. |
| `select-model` | `select-model <name> [--screenshot P]` | Open model selector → pick first entry containing `name`. Best-effort. |

## Output JSON schema

Mutating / observe commands return:

```json
{
  "ok": true,
  "serial": "emulator-5554",
  "node_count": 42,
  "nodes": [ { ...node... } ],
  "compact": [ { ...node... } ],
  "screenshot": "/path.png"        // only when --screenshot given
}
```

Each entry in `nodes` (flat list, one per uiautomator node):

```json
{
  "index": 7,
  "text": "Save",
  "content-desc": "",
  "resource-id": "",
  "class": "android.widget.Button",
  "package": "cn.vectory.ocdroid",
  "bounds": [560, 800, 1032, 900],
  "center": [796, 850],
  "clickable": true,
  "enabled": true,
  "focused": false,
  "scrollable": false
}
```

`compact` is the same nodes filtered to those with non-empty `text` or
`content-desc`, keeping only `index/text/content-desc/resource-id/clickable/
center`. Use it to skim a screen quickly.

`bounds` is `[x1, y1, x2, y2]`; `center` is `[cx, cy]` (integer midpoint), or
`null` when a node has no bounds.

### Errors

Node lookup miss:

```json
{ "ok": false, "error": "node_not_found", "label": "Save", "compact": [...] }
```

adb failure — passed through verbatim (exit code 2 from the CLI):

```json
{
  "error": "adb_command_failed",
  "adb_args": ["shell", "input", "tap", "1", "2"],
  "exit_code": 1,
  "stdout": "",
  "stderr": "error: more than one device/emulator"
}
```

We deliberately do **not** collapse adb errors into a generic message: the raw
exit code and stderr are surfaced so the caller can act on them.

## The pitfalls this CLI absorbs

1. **`input text` drops spaces.** `adb shell input text "hello world"` loses
   everything after the space. Fix: encode spaces as `%s` and backslash-escape
   shell metachars (`escape_input_text`). Applied by `type-text`, `send-prompt`,
   `configure-server`.
2. **The Send button moves with the keyboard.** Its position is not fixed next
   to the input. `send-prompt` re-dumps the tree after typing and finds Send by
   content-desc `"Send"`, then taps that node's live center — never a cached
   coordinate.
3. **Tapping a bottom-nav tab while the keyboard is up re-focuses the input.**
   `configure-server` presses BACK to dismiss the keyboard before switching to
   the Settings tab (and again before tapping Test/Save).
4. **New tool cards can sit below the fold in a long conversation.**
   `scroll-to-text` / `tree --scroll-find` swipe down until the label appears or
   the tree stops changing (bottom reached).

## Architecture

Pure logic and adb IO are separated so the logic is unit-testable without an
emulator:

- `ui_driver/parsing.py` — pure: XML → node list/JSON, find-by-label, center
  math, `%s`/metachar escaping, and the step plans for the high-level
  sequences. No subprocess, no adb.
- `ui_driver/adb.py` — thin adb wrapper (the single IO seam; mocked in tests).
- `ui_driver/driver.py` — command implementations that orchestrate the two.
- `ui_driver/cli.py` — argparse entry point (`python -m ui_driver`).

## Tests

```bash
source .venv/bin/activate
python -m pytest -q
```

- Unit tests (`test_parsing.py`, `test_driver.py`, `test_adb_errors.py`) run
  with no emulator: parsing is fed XML fixtures, the driver runs against a
  `FakeAdb` that records calls.
- Integration tests (`test_integration.py`) are marked `integration` and
  **skipped by default**. To run against a real emulator:

  ```bash
  UI_DRIVER_RUN_INTEGRATION=1 pytest -m integration tests/test_integration.py
  ```

  They require a running emulator with the client installed (optionally
  `OPENCODE_URL` / `OPENCODE_USER` / `OPENCODE_PASS`).
