"""Command implementations: orchestrate the IO layer (Adb) using the pure
planning/parsing logic, and build the agent-facing JSON result.

Every command that mutates the UI returns the post-action UI tree. The CLI
serializes whatever dict these methods return.
"""

from __future__ import annotations

from typing import Optional

from . import parsing
from .adb import Adb


class Driver:
    def __init__(self, adb: Adb):
        self.adb = adb
        # Scratch space for high-level sequences to record observations made
        # mid-plan (e.g. whether the connection test reported "Connected"
        # before a later Save tap cleared the transient status).
        self._plan_observations: dict = {}

    # ---- result building ------------------------------------------------
    def _observe(self, screenshot: Optional[str] = None,
                 scroll_find: Optional[str] = None) -> dict:
        """Dump the current tree (optionally scrolling to find a label first)
        and assemble the standard result dict: full nodes + compact view."""
        if scroll_find:
            self._scroll_until(scroll_find)
        xml = self.adb.dump_ui_xml()
        nodes = parsing.parse_ui_tree(xml)
        result = {
            "ok": True,
            "serial": self.adb.serial,
            "node_count": len(nodes),
            "nodes": nodes,
            "compact": parsing.compact_nodes(nodes),
        }
        if screenshot:
            self.adb.screenshot(screenshot)
            result["screenshot"] = screenshot
        return result

    # ---- low-level commands --------------------------------------------
    def tree(self, screenshot=None, scroll_find=None) -> dict:
        return self._observe(screenshot=screenshot, scroll_find=scroll_find)

    def tap_xy(self, x: int, y: int, screenshot=None) -> dict:
        self.adb.tap(x, y)
        return self._observe(screenshot=screenshot)

    def tap_text(self, label: str, screenshot=None) -> dict:
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        node = parsing.find_node_by_label(nodes, label)
        if not node or not node["center"]:
            return self._not_found(label, nodes)
        cx, cy = node["center"]
        self.adb.tap(cx, cy)
        return self._observe(screenshot=screenshot)

    def type_text(self, text: str, screenshot=None) -> dict:
        # Pitfall #1: escape spaces -> %s and shell metachars before sending.
        self.adb.input_text_escaped(parsing.escape_input_text(text))
        return self._observe(screenshot=screenshot)

    def clear_field(self, screenshot=None) -> dict:
        self.adb.clear_field()
        return self._observe(screenshot=screenshot)

    def key(self, name: str, screenshot=None) -> dict:
        mapping = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME",
                   "enter": "KEYCODE_ENTER"}
        code = mapping.get(name)
        if not code:
            return {"ok": False, "error": "unknown_key", "key": name,
                    "allowed": list(mapping)}
        self.adb.keyevent(code)
        return self._observe(screenshot=screenshot)

    def launch(self, screenshot=None) -> dict:
        self.adb.launch()
        self.adb.sleep(2)
        return self._observe(screenshot=screenshot)

    def clear_data(self) -> dict:
        self.adb.clear_data()
        return {"ok": True, "serial": self.adb.serial, "action": "clear-data"}

    def inject_credentials(self, url: str, username: str, password: str,
                           screenshot=None) -> dict:
        """Debug-only fast path: launch MainActivity with the connection
        credentials as Intent string extras, so the app's debug-gated injection
        configures the server (persists to EncryptedSharedPreferences + connects)
        without us driving the Settings UI at all.

        This sidesteps `configure-server`: no tab switch, no field typing, no
        Test Connection tap (which could hang). The values are passed as `am
        start --es <key> <value>` argv elements (see Adb.launch / parsing
        .am_start_extra_args), so a password containing '@' or spaces is
        delivered verbatim. Returns the post-launch UI tree like other mutating
        commands.
        """
        self.adb.launch(string_extras={
            "test_server_url": url,
            "test_username": username,
            "test_password": password,
        })
        # The debug injection runs configureServer (persist + repository
        # connect) at launch; give it a moment to render before observing.
        self.adb.sleep(1)
        return self._observe(screenshot=screenshot)

    def scroll_to_text(self, label: str, screenshot=None) -> dict:
        found = self._scroll_until(label)
        result = self._observe(screenshot=screenshot)
        result["found"] = found
        return result

    # ---- high-level sequences ------------------------------------------
    def configure_server(self, url: str, username: str, password: str,
                          screenshot=None) -> dict:
        plan = parsing.plan_configure_server(url, username, password)
        # _execute_plan records whether a wait-for-label ("Connected") resolved,
        # because tapping Save afterwards can clear the transient status text, so
        # the final tree may not still show it. We surface that captured result.
        self._plan_observations = {}
        self._execute_plan(plan)
        # Give the Save/connection result a moment to render before observing.
        self.adb.sleep(1)
        result = self._observe(screenshot=screenshot)
        # "Connected" status seen during the test (before Save possibly cleared
        # it), so the caller has a stable signal of connection success.
        result["connected"] = self._plan_observations.get("Connected", False)
        return result

    def send_prompt(self, text: str, screenshot=None, max_retries: int = 2) -> dict:
        # Focus the chat input. Find the first EditText in the current tree.
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        edit = next((n for n in nodes if "EditText" in n["class"] and n["center"]), None)
        if not edit:
            return self._not_found("<chat input EditText>", nodes)
        self.adb.tap(*edit["center"])
        self.adb.sleep(0.3)
        self.adb.input_text_escaped(parsing.escape_input_text(text))
        self.adb.sleep(0.3)
        # Real-device root cause: the Send button slides ~830px down the moment
        # the soft keyboard collapses (keyboard-up center ~[976,1133],
        # keyboard-down ~[976,1966]). If we dump for Send before the IME has
        # finished animating away, the dump reports the keyboard-UP coordinate;
        # tapping it misses (Send has since moved) and the message stays in the
        # box. So we dismiss the keyboard FIRST (conditionally — only when shown,
        # so we never blind-BACK off the screen), then WAIT for the IME to be
        # fully hidden before dumping, so Send is resolved at its settled
        # position. Dumping with the keyboard down also avoids perturbing focus.
        self._dismiss_keyboard()
        # Tap Send, then verify the prompt actually left the input box. Each
        # attempt re-dumps and re-resolves Send live (pitfall #2 — bounds shift;
        # never cache a coordinate). We prefer the *enabled* Send node: it is
        # disabled while the box is empty, so an enabled Send is the live one.
        for attempt in range(max_retries + 1):
            nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
            send = parsing.find_send_button(nodes)
            if not send or not send["center"]:
                return self._not_found("Send", nodes)
            self.adb.tap(*send["center"])
            self.adb.sleep(0.5)
            if self._sent_ok(text):
                result = self._observe(screenshot=screenshot)
                result["sent"] = True
                result["send_attempts"] = attempt + 1
                return result
        # Could not confirm the message left the input box after retries.
        result = self._observe(screenshot=screenshot)
        result["ok"] = False
        result["error"] = "send_not_confirmed"
        result["sent"] = False
        result["send_attempts"] = max_retries + 1
        return result

    def _dismiss_keyboard(self) -> None:
        """Conditional BACK to collapse the IME, then wait for it to settle.

        Mirrors the configure-server `dismiss-keyboard` step: a blind BACK on a
        keyboard-less screen would navigate away, so we gate on
        is_keyboard_shown(). We then poll until the keyboard is actually hidden
        before returning, because the dismiss is animated: dumping for Send
        mid-animation reports its keyboard-UP coordinate and the tap misses."""
        if self.adb.is_keyboard_shown():
            self.adb.keyevent("KEYCODE_BACK")
            self.adb.wait_for_keyboard_hidden()
            # Small extra settle for the relayout after the IME is reported down.
            self.adb.sleep(0.4)

    def _sent_ok(self, text: str, timeout: float = 4.0,
                 interval: float = 0.5) -> bool:
        """Poll the tree until the prompt is no longer sitting in the input box.

        Returns True once the input field no longer contains `text` (the send
        went through), or False if it is still there after `timeout`. The box
        can keep the text visible for a moment after Send fires (the IME / list
        repaints), so we poll rather than checking once."""
        waited = 0.0
        while True:
            nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
            if not parsing.prompt_still_in_input(nodes, text):
                return True
            if waited >= timeout:
                return False
            self.adb.sleep(interval)
            waited += interval

    def select_model(self, name: str, screenshot=None) -> dict:
        # Best-effort: open a model selector then pick the matching entry.
        # The selector trigger is not a stable label in the source, so we look
        # for a node containing 'model' (case-insensitive) to open it.
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        opener = parsing.find_node_by_label(nodes, "model", clickable_only=True)
        if opener and opener["center"]:
            self.adb.tap(*opener["center"])
            self.adb.sleep(0.5)
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        choice = parsing.find_node_by_label(nodes, name, clickable_only=True) \
            or parsing.find_node_by_label(nodes, name)
        if not choice or not choice["center"]:
            return self._not_found(name, nodes)
        self.adb.tap(*choice["center"])
        self.adb.sleep(0.3)
        return self._observe(screenshot=screenshot)

    # ---- internals ------------------------------------------------------
    def _execute_plan(self, plan: list[dict]) -> None:
        """Interpret a pure step plan against the live device. Each tap-label
        / tap-field re-dumps the tree so it acts on current coordinates."""
        for step in plan:
            op = step["op"]
            if op == "key":
                code = {"back": "KEYCODE_BACK", "home": "KEYCODE_HOME",
                        "enter": "KEYCODE_ENTER"}[step["key"]]
                self.adb.keyevent(code)
                self.adb.sleep(0.4)
            elif op == "dismiss-keyboard":
                # Pitfall #3: only press BACK when the IME is actually shown.
                # On the app root no keyboard is up, so a blind BACK would exit
                # the app to the launcher and the rest of the plan would then
                # operate on the launcher instead of Settings.
                if self.adb.is_keyboard_shown():
                    self.adb.keyevent("KEYCODE_BACK")
                    self.adb.sleep(0.4)
            elif op == "tap-label" or op == "tap-label-dynamic":
                nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
                # Prefer a clickable match when one exists, but fall back to the
                # first label match: Compose nav tabs / buttons surface the
                # label on a non-clickable TextView, so a hard clickable_only
                # filter would find nothing and skip the tap (real-device bug).
                node = parsing.find_node_by_label(
                    nodes, step["label"],
                    clickable_only=step.get("clickable_only", False),
                    prefer_clickable=True)
                if node and node["center"]:
                    self.adb.tap(*node["center"])
                    self.adb.sleep(0.4)
            elif op == "tap-field":
                nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
                node = parsing.find_field_by_label(nodes, step["label"]) \
                    if step["label"] else \
                    next((n for n in nodes if "EditText" in n["class"]), None)
                if node and node["center"]:
                    self.adb.tap(*node["center"])
                    self.adb.sleep(0.3)
            elif op == "clear-field":
                self.adb.clear_field()
            elif op == "type":
                self.adb.input_text_escaped(parsing.escape_input_text(step["text"]))
                self.adb.sleep(0.2)
            elif op == "wait":
                self.adb.sleep(step["seconds"])
            elif op == "wait-for-label":
                # Poll the tree until `label` appears or `timeout` elapses. Used
                # for async results (e.g. the connection test resolves in a few
                # seconds) so we don't race ahead with a brittle fixed sleep.
                seen = self._wait_for_label(step["label"], step.get("timeout", 10))
                # Record for the caller; the label may be cleared by a later
                # step (Save), so the final tree alone can't be trusted.
                self._plan_observations[step["label"]] = seen

    def _wait_for_label(self, label: str, timeout: float,
                        interval: float = 0.5) -> bool:
        """Re-dump the tree until `label` is present, or timeout. Returns
        whether it appeared. Best-effort: never raises on absence (the caller
        proceeds and the final observe reflects the real state)."""
        waited = 0.0
        while waited < timeout:
            nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
            if parsing.find_node_by_label(nodes, label):
                return True
            self.adb.sleep(interval)
            waited += interval
        return False

    def _scroll_until(self, label: str, max_swipes: int = 12) -> bool:
        """Scroll until `label` is visible, searching BOTH directions.

        Pitfall #4: a tool card may sit off-screen. Crucially it can be *above*
        the current viewport, not just below: the latest assistant message sits
        at the bottom, and the tool cards it produced render just above it, so a
        card can be off the top of the fold. The old one-direction (toward the
        bottom) scroll pushed such a card further away and never found it.

        We first scroll toward the bottom until the label appears or the tree
        stops moving, then reverse and scroll toward the top. Either pass
        returning True short-circuits."""
        if self._scroll_dir_until(label, "down", max_swipes):
            return True
        # Not found scrolling toward the bottom; reverse and scan upward. We
        # allow more swipes here so we can climb back past where we started.
        if self._scroll_dir_until(label, "up", max_swipes * 2):
            return True
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        return parsing.find_node_by_label(nodes, label) is not None

    def _scroll_dir_until(self, label: str, direction: str,
                          max_swipes: int) -> bool:
        """Swipe repeatedly in one `direction` ('down' moves content toward the
        bottom; 'up' toward the top) until `label` appears or the tree stops
        changing (reached that end). Returns whether the label became visible."""
        prev_signature = None
        for _ in range(max_swipes):
            nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
            if parsing.find_node_by_label(nodes, label):
                return True
            # Find the largest scrollable node to swipe within; fall back to a
            # generic mid-screen swipe.
            scrollable = next((n for n in nodes if n["scrollable"] and n["bounds"]), None)
            if scrollable:
                x1, y1, x2, y2 = scrollable["bounds"]
                cx = (x1 + x2) // 2
                near = int(y2 - (y2 - y1) * 0.25)
                far = int(y1 + (y2 - y1) * 0.25)
            else:
                cx, near, far = 540, 1500, 600
            if direction == "down":
                # Drag finger up -> content moves toward the bottom.
                self.adb.swipe(cx, near, cx, far)
            else:
                # Drag finger down -> content moves toward the top.
                self.adb.swipe(cx, far, cx, near)
            self.adb.sleep(0.5)
            signature = tuple((n["text"], n["bounds"][1] if n["bounds"] else None)
                              for n in nodes[:5])
            if signature == prev_signature:
                break  # tree unchanged -> reached this end
            prev_signature = signature
        nodes = parsing.parse_ui_tree(self.adb.dump_ui_xml())
        return parsing.find_node_by_label(nodes, label) is not None

    def _not_found(self, label: str, nodes: list[dict]) -> dict:
        return {
            "ok": False,
            "error": "node_not_found",
            "label": label,
            "serial": self.adb.serial,
            "compact": parsing.compact_nodes(nodes),
        }
