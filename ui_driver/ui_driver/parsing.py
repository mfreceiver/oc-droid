"""Pure-logic layer: no IO, no adb. Everything here is unit-testable by
feeding XML fixture strings. Keep this module free of subprocess/adb calls so
the parsing / node-finding / escaping logic can be verified without an emulator.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from typing import Optional


# Attributes we surface from each uiautomator node. We always emit these keys
# (with sensible defaults) so the agent-facing JSON schema is stable.
_BOOL_ATTRS = {"clickable", "enabled", "focused", "focusable", "scrollable",
               "selected", "checked", "checkable", "long-clickable",
               "password"}


def parse_bounds(bounds_str: str) -> Optional[list[int]]:
    """Parse uiautomator bounds '[x1,y1][x2,y2]' -> [x1, y1, x2, y2].

    Returns None when the string is empty or malformed, rather than raising,
    because a missing bounds attr should not blow up a whole tree dump.
    """
    if not bounds_str:
        return None
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds_str.strip())
    if not m:
        return None
    return [int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))]


def center_of(bounds: Optional[list[int]]) -> Optional[list[int]]:
    """Center point [cx, cy] of a [x1,y1,x2,y2] box, or None if no bounds."""
    if not bounds:
        return None
    x1, y1, x2, y2 = bounds
    return [(x1 + x2) // 2, (y1 + y2) // 2]


def _node_to_dict(el: ET.Element, index: int) -> dict:
    """Flatten a single XML node element into our stable JSON node schema."""
    attrib = el.attrib
    bounds = parse_bounds(attrib.get("bounds", ""))
    node: dict = {
        "index": index,
        "text": attrib.get("text", ""),
        "content-desc": attrib.get("content-desc", ""),
        "resource-id": attrib.get("resource-id", ""),
        "class": attrib.get("class", ""),
        "package": attrib.get("package", ""),
        "bounds": bounds,
        "center": center_of(bounds),
        "clickable": attrib.get("clickable", "false") == "true",
        "enabled": attrib.get("enabled", "true") == "true",
        "focused": attrib.get("focused", "false") == "true",
        "scrollable": attrib.get("scrollable", "false") == "true",
    }
    return node


def parse_ui_tree(xml_text: str) -> list[dict]:
    """Parse a uiautomator XML dump into a flat list of node dicts.

    We flatten rather than nest because agents almost always want to search
    by text/desc/resource-id, and a flat list with an index per node is the
    easiest thing to reason about and to point a follow-up command at.
    """
    if not xml_text or not xml_text.strip():
        return []
    # uiautomator occasionally prepends a status line before the XML; trim to
    # the first '<' so ET.fromstring does not choke on it.
    start = xml_text.find("<")
    if start > 0:
        xml_text = xml_text[start:]
    root = ET.fromstring(xml_text)
    nodes: list[dict] = []
    counter = {"i": 0}

    def walk(el: ET.Element) -> None:
        # The synthetic <hierarchy> root carries no UI attrs; skip it but keep
        # walking its children.
        if el.tag == "node":
            nodes.append(_node_to_dict(el, counter["i"]))
            counter["i"] += 1
        for child in list(el):
            walk(child)

    walk(root)
    return nodes


def compact_nodes(nodes: list[dict]) -> list[dict]:
    """A trimmed view: only nodes with non-empty text or content-desc.

    This is what an agent skims first to understand the screen without wading
    through hundreds of layout containers.
    """
    out = []
    for n in nodes:
        if n["text"] or n["content-desc"]:
            out.append({
                "index": n["index"],
                "text": n["text"],
                "content-desc": n["content-desc"],
                "resource-id": n["resource-id"],
                "clickable": n["clickable"],
                "center": n["center"],
            })
    return out


def find_nodes_by_label(nodes: list[dict], label: str,
                        clickable_only: bool = False) -> list[dict]:
    """All nodes whose text OR content-desc contains `label` (substring match).

    Substring (not exact) match because labels in the spec ('Send', 'Settings')
    are how the caller thinks, and Compose nodes sometimes carry extra text.
    """
    label_l = label.lower()
    out = []
    for n in nodes:
        hay = f"{n['text']} {n['content-desc']}".lower()
        if label_l in hay:
            if clickable_only and not n["clickable"]:
                continue
            out.append(n)
    return out


def find_node_by_label(nodes: list[dict], label: str,
                       clickable_only: bool = False,
                       prefer_clickable: bool = False) -> Optional[dict]:
    """First matching node, or None. Used by tap-text / send-prompt.

    `prefer_clickable` (used by tap-label) returns the first *clickable* match
    when one exists, else falls back to the first match. This is the right
    default for "tap this thing": in some screens the label sits on a clickable
    container (a nav tab / button), in others (Jetpack Compose) the clickable
    flag isn't surfaced on any matching node at all, so a hard clickable_only
    filter would find nothing and silently skip the tap.
    """
    matches = find_nodes_by_label(nodes, label, clickable_only=clickable_only)
    if not matches:
        return None
    if prefer_clickable:
        for n in matches:
            if n["clickable"]:
                return n
    return matches[0]


def find_field_by_label(nodes: list[dict], label: str) -> Optional[dict]:
    """Locate an editable field associated with a label.

    Compose text fields expose their label as the node's text/content-desc on
    an EditText-class node. We prefer an EditText whose text/desc matches; if
    none, fall back to any matching node (the field may render the label on a
    sibling). The caller taps this to focus before typing.
    """
    matches = find_nodes_by_label(nodes, label)
    edits = [n for n in matches if "EditText" in n["class"]]
    if edits:
        return edits[0]
    return matches[0] if matches else None


# adb shell `input text` mangles certain characters. Spaces in particular get
# silently dropped unless encoded as %s. We encode the known-dangerous set so
# typed prompts survive intact. (Pitfall #1 from the task spec.)
#
# We escape shell-meta + characters `input text` treats specially. Order
# matters only in that we do NOT need to touch '%' itself for normal text, but
# a literal '%' would be misread, so we leave it alone deliberately and let the
# caller avoid percent-literals (documented in README).
_INPUT_TEXT_REPLACEMENTS = [
    (" ", "%s"),
    ("&", "\\&"),
    ("<", "\\<"),
    (">", "\\>"),
    ("?", "\\?"),
    ("|", "\\|"),
    ("(", "\\("),
    (")", "\\)"),
    (";", "\\;"),
    ("`", "\\`"),
    ('"', '\\"'),
    ("'", "\\'"),
    ("$", "\\$"),
]


def escape_input_text(text: str) -> str:
    """Escape text for `adb shell input text`.

    Pitfall #1: a raw space makes `input text` drop everything after it (the
    shell word-splits the argument). The canonical fix is to send spaces as
    %s. We additionally backslash-escape shell metacharacters so prompts with
    punctuation don't get reinterpreted by the device shell.
    """
    out = text
    for src, dst in _INPUT_TEXT_REPLACEMENTS:
        out = out.replace(src, dst)
    return out


def am_start_extra_args(string_extras: dict) -> list[str]:
    """Build the `--es key value` argv tail for `am start` string extras.

    Returns a flat argv list, e.g. {"a": "1", "b": "x y"} ->
    ["--es", "a", "1", "--es", "b", "x y"]. Each key and value is its own argv
    element, so subprocess passes them to adb literally — there is NO device
    shell to word-split them, so values with spaces, '@', ':', '/', etc. (e.g. a
    password like 'p@ss w:rd' or a URL) survive verbatim. We deliberately do not
    quote/escape here: adding shell quoting would be wrong, because these are
    exec argv elements, not a shell string. Order follows dict insertion order
    so callers (and tests) get a deterministic argv.
    """
    args: list[str] = []
    for key, value in string_extras.items():
        args += ["--es", key, value]
    return args


# ---- Action-sequence step planning (pure) --------------------------------
# These functions return the ordered list of abstract steps a high-level
# command performs. Returning data (not doing IO) lets us unit-test that the
# orchestration order is correct without an emulator. The IO layer interprets
# each step.


def plan_configure_server(url: str, username: str, password: str) -> list[dict]:
    """Step plan for `configure-server`.

    Pitfall #3: the keyboard must be dismissed before switching tabs / tapping
    buttons, but a *blind* BACK is wrong. On the app root no keyboard is up, so
    BACK exits the app to the launcher and the rest of the plan then operates on
    the launcher instead of Settings. We therefore emit `dismiss-keyboard`, a
    conditional op the IO layer only turns into BACK when the IME is actually
    shown (Adb.is_keyboard_shown()).
    """
    # NB: we do NOT set clickable_only here. The app is Jetpack Compose; its
    # nav tabs and Settings buttons surface their label on a non-clickable
    # TextView nested inside a clickable container, so uiautomator reports
    # clickable=false on the labeled node. Requiring clickable_only would make
    # find_node_by_label return None and silently skip every tap. Tapping the
    # label node's center lands inside the clickable container, which works.
    steps: list[dict] = [
        {"op": "dismiss-keyboard", "why": "collapse IME before tab switch (only if shown)"},
        {"op": "tap-label", "label": "Settings"},
        {"op": "tap-field", "label": "Server URL"},
        {"op": "clear-field"},
        {"op": "type", "text": url},
        {"op": "tap-field", "label": "Username"},
        {"op": "clear-field"},
        {"op": "type", "text": username},
        {"op": "tap-field", "label": "Password"},
        {"op": "clear-field"},
        {"op": "type", "text": password},
        {"op": "dismiss-keyboard", "why": "collapse IME so buttons are visible (only if shown)"},
        {"op": "tap-label", "label": "Test Connection"},
        {"op": "wait-for-label", "label": "Connected", "timeout": 20,
         "why": "the connection test is async (several seconds on a live "
                "server, occasionally longer); poll for the 'Connected' result "
                "instead of a brittle fixed sleep"},
        {"op": "tap-label", "label": "Save"},
    ]
    return steps


def plan_send_prompt(text: str) -> list[dict]:
    """Step plan for `send-prompt`.

    Pitfall #2: the Send button moves when the keyboard is up, so the plan
    records that Send must be located from the *current* tree by content-desc
    (resolved at exec time), never a fixed coordinate.

    Real-device bug (confirmed on emulator-5554): the Send button slides
    ~830px down the instant the soft keyboard collapses (keyboard-up center
    ~[976,1133], keyboard-down ~[976,1966]). If we dump for Send while the IME
    is still animating away, the dump reports the keyboard-UP coordinate; the
    tap then lands where Send no longer is and the message stays in the box.
    The fix (see Driver.send_prompt) is to *conditionally* dismiss the keyboard
    BEFORE locating Send, WAIT for the IME to be fully hidden, then dump so Send
    is resolved at its settled position. The dismiss is conditional (only when
    mInputShown=true) so we never blind-press BACK off the screen. We also
    prefer the *enabled* Send node (it is disabled while the box is empty) and
    verify the prompt actually left the input field, retrying if it did not.
    """
    return [
        {"op": "tap-field", "label": "", "input_class": "EditText",
         "why": "focus the chat input (first editable field)"},
        {"op": "type", "text": text},
        {"op": "dismiss-keyboard",
         "why": "collapse IME before dumping for Send; dumping with the "
                "keyboard up perturbs input focus and the Send tap misfires"},
        {"op": "tap-label-dynamic", "label": "Send", "clickable_only": True,
         "why": "Send button bounds shift with keyboard; resolve live"},
        {"op": "verify-input-cleared", "text": text,
         "why": "confirm the prompt left the input box (message actually sent); "
                "retry the Send tap if it is still there"},
    ]


def find_send_button(nodes: list[dict]) -> Optional[dict]:
    """Locate the chat Send *button* specifically.

    Real-device bug: a plain substring search for 'Send' also matches the empty
    chat's placeholder TextView "No messages yet. Send a message to start.",
    which sorts before the real button in the tree. Tapping that placeholder
    does nothing, so on a fresh session send-prompt would silently fail. We
    therefore restrict to the actual control:
      1. a node whose content-desc is exactly 'Send' (the Button exposes its
         label there) — prefer an enabled, tappable one;
      2. else a clickable node matching 'Send';
      3. else fall back to the first 'Send' match (keeps older trees working).
    Disabled controls (greyed while the box is empty) are skipped in favour of
    an enabled one so we tap the live button.
    """
    matches = find_nodes_by_label(nodes, "Send")
    if not matches:
        return None

    def usable(n: dict) -> bool:
        return bool(n["center"]) and n.get("enabled", True)

    exact = [n for n in matches if n["content-desc"].strip() == "Send"]
    for pool in (exact, [n for n in matches if n["clickable"]]):
        enabled = [n for n in pool if usable(n)]
        if enabled:
            return enabled[0]
        if pool:
            return pool[0]
    return matches[0]


def input_field_value(nodes: list[dict]) -> str:
    """Best-effort current text of the chat input (first EditText's text).

    Used to verify a send actually happened: after Send fires the input box
    empties, so a non-empty value that still contains the prompt means the tap
    misfired. Returns '' when no EditText is present.
    """
    edit = next((n for n in nodes if "EditText" in n["class"]), None)
    return edit["text"] if edit else ""


def prompt_still_in_input(nodes: list[dict], text: str) -> bool:
    """Whether the (stripped) prompt text is still sitting in the input box.

    True means the send did NOT go through (message remained in the field).
    Compares case-sensitively on a stripped substring so trailing whitespace
    or an IME-added space does not mask a genuine 'still there'.
    """
    value = input_field_value(nodes).strip()
    needle = (text or "").strip()
    return bool(needle) and needle in value


# ---- device-state parsers (pure) -----------------------------------------
# Parse the stdout of `dumpsys` queries so the keyboard / foreground decisions
# are unit-testable without a device. The IO layer feeds these the raw text.


def parse_keyboard_shown(dumpsys_input_method: str) -> bool:
    """True iff the IME is currently shown, per `dumpsys input_method`.

    The relevant line is `mInputShown=true` / `mInputShown=false`. We match the
    last occurrence (some builds print it more than once) and default to False
    when the field is absent so we never blindly press BACK on the app root.
    """
    last = None
    for m in re.finditer(r"mInputShown=(true|false)", dumpsys_input_method or ""):
        last = m.group(1)
    return last == "true"


def parse_top_activity(dumpsys_activity_activities: str) -> Optional[str]:
    """Extract the foreground activity component from `dumpsys activity
    activities`.

    We look for `topResumedActivity` and return the `pkg/.Activity` token from
    that line (e.g. 'com.yage.opencode_client/.MainActivity'), or None if the
    line is absent. Some API levels label it `mResumedActivity`; we accept that
    too as a fallback.
    """
    text = dumpsys_activity_activities or ""
    for key in ("topResumedActivity", "mResumedActivity"):
        for line in text.splitlines():
            if key in line:
                m = re.search(r"(\S+/\S+)", line)
                if m:
                    return m.group(1).rstrip("}")
    return None
