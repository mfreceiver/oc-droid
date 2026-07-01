"""Driver orchestration tests using a FakeAdb. Still no emulator: we record
the adb calls the driver would make and assert on their order/content."""

import os

from ui_driver.driver import Driver

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def _load(name):
    with open(os.path.join(FIXTURE_DIR, name), encoding="utf-8") as fh:
        return fh.read()


class FakeAdb:
    """Records every action and serves a scripted sequence of XML dumps."""

    PACKAGE = "cn.vectory.ocdroid"
    ACTIVITY = ".MainActivity"

    def __init__(self, xml_sequence, keyboard_shown=False):
        self.serial = "emulator-5554"
        self._xml = list(xml_sequence)
        self.calls = []
        self._keyboard_shown = keyboard_shown

    def _next_xml(self):
        # Repeat the last fixture once the script is exhausted.
        return self._xml.pop(0) if len(self._xml) > 1 else self._xml[0]

    def dump_ui_xml(self):
        self.calls.append(("dump",))
        return self._next_xml()

    def tap(self, x, y):
        self.calls.append(("tap", x, y))

    def input_text_escaped(self, escaped):
        self.calls.append(("type", escaped))

    def keyevent(self, code):
        self.calls.append(("key", code))

    def clear_field(self, repeats=80):
        self.calls.append(("clear", repeats))

    def swipe(self, x1, y1, x2, y2, ms=300):
        self.calls.append(("swipe", x1, y1, x2, y2))

    def is_keyboard_shown(self):
        self.calls.append(("is_keyboard_shown",))
        return self._keyboard_shown

    def wait_for_keyboard_hidden(self, timeout=2.0, interval=0.2):
        self.calls.append(("wait_for_keyboard_hidden",))
        # Pressing BACK collapsed it; reflect that for any later checks.
        self._keyboard_shown = False
        return True

    def wait_for_foreground(self, component=None, timeout=5.0, interval=0.25):
        self.calls.append(("wait_for_foreground", component))
        return True

    def launch(self, string_extras=None):
        self.calls.append(("launch", string_extras))

    def clear_data(self):
        self.calls.append(("clear-data",))

    def screenshot(self, path):
        self.calls.append(("screenshot", path))
        return path

    @staticmethod
    def sleep(_seconds):
        pass


def test_tree_returns_nodes_and_compact():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.tree()
    assert out["ok"] is True
    assert out["node_count"] > 0
    assert any(c["content-desc"] == "Server URL" for c in out["compact"])


def test_tap_text_uses_resolved_center():
    adb = FakeAdb([_load("settings_screen.xml"), _load("settings_screen.xml")])
    d = Driver(adb)
    d.tap_text("Save")
    # Save button center: [(560+1032)/2, (800+900)/2] = [796, 850]
    assert ("tap", 796, 850) in adb.calls


def test_tap_text_not_found_returns_error():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.tap_text("DoesNotExist")
    assert out["ok"] is False
    assert out["error"] == "node_not_found"


def test_type_text_escapes_before_send():
    adb = FakeAdb([_load("chat_screen.xml"), _load("chat_screen.xml")])
    d = Driver(adb)
    d.type_text("hello world")
    assert ("type", "hello%sworld") in adb.calls


def test_configure_server_full_sequence():
    # Many dumps happen during the plan; serve settings xml throughout.
    # Keyboard NOT shown (app root): BACK must NOT be pressed (pitfall #3),
    # otherwise we'd exit the app to the launcher.
    adb = FakeAdb([_load("settings_screen.xml")], keyboard_shown=False)
    d = Driver(adb)
    out = d.configure_server("http://h:9", "alice", "pw")
    assert ("key", "KEYCODE_BACK") not in adb.calls
    # The keyboard state was checked at each dismiss-keyboard step.
    assert ("is_keyboard_shown",) in adb.calls
    # Settings tab center: [(720+1080)/2,(2200+2340)/2]=[900,2270]
    assert ("tap", 900, 2270) in adb.calls
    # Typed escaped values present in order.
    typed = [c[1] for c in adb.calls if c[0] == "type"]
    assert typed == ["http://h:9", "alice", "pw"]
    # Test Connection (center [284,850]) then Save ([796,850]) tapped.
    assert ("tap", 284, 850) in adb.calls
    assert ("tap", 796, 850) in adb.calls
    assert out["ok"] is True


def test_configure_server_dismisses_keyboard_when_shown():
    # Keyboard IS up: each dismiss-keyboard step must press BACK to collapse it.
    adb = FakeAdb([_load("settings_screen.xml")], keyboard_shown=True)
    d = Driver(adb)
    out = d.configure_server("http://h:9", "alice", "pw")
    assert ("key", "KEYCODE_BACK") in adb.calls
    # Two dismiss-keyboard steps in the plan -> two BACK presses.
    assert sum(1 for c in adb.calls if c == ("key", "KEYCODE_BACK")) == 2
    assert out["ok"] is True


def test_launch_waits_for_foreground():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.launch()
    assert ("launch", None) in adb.calls
    assert out["ok"] is True


def test_send_prompt_locates_send_live():
    # Keyboard down: the chat fixture's input box is empty, so the send is
    # confirmed on the first attempt.
    adb = FakeAdb([_load("chat_screen.xml")], keyboard_shown=False)
    d = Driver(adb)
    out = d.send_prompt("run a test")
    # First taps the EditText center [(48+900)/2,(1850+1990)/2]=[474,1920]
    assert ("tap", 474, 1920) in adb.calls
    # Types escaped prompt.
    assert ("type", "run%sa%stest") in adb.calls
    # Then taps the Send button center [980,1920] resolved from the tree.
    assert ("tap", 980, 1920) in adb.calls
    assert out["ok"] is True
    assert out["sent"] is True


def test_send_prompt_dismisses_keyboard_before_locating_send():
    # Real-device bug: dumping the tree to locate Send while the IME is up
    # perturbs focus and the Send tap misfires. The driver must collapse the
    # keyboard (conditional BACK) BEFORE the dump that resolves Send.
    adb = FakeAdb([_load("chat_screen.xml")], keyboard_shown=True)
    d = Driver(adb)
    out = d.send_prompt("run a test")
    # Keyboard was up, so exactly one BACK collapsed it before the Send dump,
    # and the driver waited for the IME to settle (so Send is dumped at its
    # keyboard-down position, not the stale keyboard-up one).
    assert ("is_keyboard_shown",) in adb.calls
    assert ("key", "KEYCODE_BACK") in adb.calls
    assert ("wait_for_keyboard_hidden",) in adb.calls
    # The conditional dismiss + settle wait happens after typing and before Send.
    type_idx = adb.calls.index(("type", "run%sa%stest"))
    back_idx = adb.calls.index(("key", "KEYCODE_BACK"))
    settle_idx = adb.calls.index(("wait_for_keyboard_hidden",))
    send_idx = adb.calls.index(("tap", 980, 1920))
    assert type_idx < back_idx < settle_idx < send_idx
    assert out["sent"] is True


def test_send_prompt_reresolves_send_at_moved_bounds():
    # The driver resolves Send live from the current tree (never a cached
    # coordinate): if Send sits at a shifted position, the tap must follow it.
    chat = _load("chat_screen.xml")
    moved = chat.replace('bounds="[920,1850][1040,1990]"',
                         'bounds="[840,1640][960,1760]"', 1)
    adb = FakeAdb([moved], keyboard_shown=False)
    d = Driver(adb)
    out = d.send_prompt("run a test")
    # Tapped the re-resolved center [(840+960)/2,(1640+1760)/2] = [900,1700].
    assert ("tap", 900, 1700) in adb.calls
    assert out["sent"] is True


def test_send_prompt_does_not_back_when_keyboard_down():
    # Keyboard already down: no BACK (a blind BACK would navigate off-screen).
    adb = FakeAdb([_load("chat_screen.xml")], keyboard_shown=False)
    d = Driver(adb)
    d.send_prompt("run a test")
    assert ("key", "KEYCODE_BACK") not in adb.calls


def test_send_prompt_polls_until_input_clears_within_attempt():
    # The box still shows the prompt on the first verify dump (UI not settled),
    # then clears on the next poll. _sent_ok polls within a single attempt, so
    # one Send tap suffices and the send is confirmed.
    chat = _load("chat_screen.xml")
    still_in_box = chat.replace(
        '<node index="1" text=""',
        '<node index="1" text="run a test"', 1)
    # Sequence: focus-dump, send-locate dump, verify#1 (still there),
    # verify#2 (cleared).
    adb = FakeAdb([chat, chat, still_in_box, chat], keyboard_shown=False)
    d = Driver(adb)
    out = d.send_prompt("run a test")
    # Single Send tap; the poll absorbed the transient unsent state.
    assert sum(1 for c in adb.calls if c == ("tap", 980, 1920)) == 1
    assert out["ok"] is True
    assert out["sent"] is True
    assert out["send_attempts"] == 1


def test_send_prompt_reports_failure_when_never_clears():
    # Input box always shows the prompt -> send never confirmed -> ok False.
    chat = _load("chat_screen.xml")
    stuck = chat.replace(
        '<node index="1" text=""',
        '<node index="1" text="run a test"', 1)
    adb = FakeAdb([stuck], keyboard_shown=False)
    d = Driver(adb)
    out = d.send_prompt("run a test", max_retries=1)
    assert out["ok"] is False
    assert out["error"] == "send_not_confirmed"
    assert out["sent"] is False


def test_screenshot_path_recorded():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.tree(screenshot="/tmp/x.png")
    assert out["screenshot"] == "/tmp/x.png"
    assert ("screenshot", "/tmp/x.png") in adb.calls


def _scroll_fixture(label_present: bool, top_text: str) -> str:
    """A minimal chat tree with one scrollable container. `label_present`
    controls whether the target card is in this dump; `top_text` varies the
    first node so the scroll signature changes between dumps (so the loop does
    not early-stop thinking it hit the end)."""
    card = ('<node index="9" text="" class="android.view.View" '
            'content-desc="Read file AGENTS.md" clickable="false" '
            'enabled="true" scrollable="false" bounds="[48,400][1000,520]" />'
            ) if label_present else ""
    return (
        "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"
        '<hierarchy rotation="0">\n'
        '  <node index="0" text="%s" class="android.widget.FrameLayout" '
        'package="p" content-desc="" clickable="false" enabled="true" '
        'scrollable="false" bounds="[0,0][1080,2340]">\n'
        '    <node index="0" text="" class="ComposeView" package="p" '
        'content-desc="" clickable="false" enabled="true" scrollable="true" '
        'bounds="[0,180][1080,1800]">\n'
        "      %s\n"
        "    </node>\n"
        "  </node>\n"
        "</hierarchy>\n" % (top_text, card)
    )


def test_scroll_finds_label_above_by_reversing_direction():
    # Card sits ABOVE the fold: scrolling toward the bottom never reveals it
    # (and hits the end), but reversing upward does. Sequence: a few
    # "absent, content shifting" dumps (downward pass to the end), then dumps
    # where the card appears (upward pass).
    label = "Read file"
    seq = [
        _scroll_fixture(False, "down-a"),
        _scroll_fixture(False, "down-b"),
        _scroll_fixture(False, "down-b"),  # signature repeats -> bottom reached
        _scroll_fixture(False, "down-b"),  # _scroll_dir_until final re-check
        _scroll_fixture(False, "up-a"),    # start upward pass, still absent
        _scroll_fixture(True, "up-b"),     # card now visible after scrolling up
    ]
    adb = FakeAdb(seq, keyboard_shown=False)
    d = Driver(adb)
    found = d._scroll_until(label, max_swipes=4)
    assert found is True
    # We must have swiped in BOTH directions. Downward: drag finger from a
    # larger y to a smaller y; upward: the reverse.
    swipes = [c for c in adb.calls if c[0] == "swipe"]
    downward = [s for s in swipes if s[2] > s[4]]
    upward = [s for s in swipes if s[2] < s[4]]
    assert downward, "expected at least one downward swipe"
    assert upward, "expected at least one upward (reverse) swipe"


def test_scroll_finds_label_below_first_pass():
    # Card appears during the downward pass: no reverse needed.
    label = "Read file"
    seq = [
        _scroll_fixture(False, "down-a"),
        _scroll_fixture(True, "down-b"),
    ]
    adb = FakeAdb(seq, keyboard_shown=False)
    d = Driver(adb)
    assert d._scroll_until(label, max_swipes=4) is True
    swipes = [c for c in adb.calls if c[0] == "swipe"]
    # Only downward swipes (finger drags from larger y to smaller y).
    assert all(s[2] >= s[4] for s in swipes)


def test_inject_credentials_launches_with_string_extras():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.inject_credentials("http://h:9", "alice", "p@ss w:rd")
    # The launch carried exactly the three test_* string extras, untouched.
    launch_calls = [c for c in adb.calls if c[0] == "launch"]
    assert launch_calls == [("launch", {
        "test_server_url": "http://h:9",
        "test_username": "alice",
        "test_password": "p@ss w:rd",
    })]
    assert out["ok"] is True


def test_clear_data_does_not_dump():
    adb = FakeAdb([_load("settings_screen.xml")])
    d = Driver(adb)
    out = d.clear_data()
    assert ("clear-data",) in adb.calls
    assert out["action"] == "clear-data"
