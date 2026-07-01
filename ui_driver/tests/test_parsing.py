"""Pure-logic unit tests. No emulator needed; everything is fed XML fixtures."""

from ui_driver import parsing


# ---- bounds / center -------------------------------------------------------
def test_parse_bounds_basic():
    assert parsing.parse_bounds("[48,300][1032,420]") == [48, 300, 1032, 420]


def test_parse_bounds_negative_and_malformed():
    assert parsing.parse_bounds("[-5,0][10,20]") == [-5, 0, 10, 20]
    assert parsing.parse_bounds("") is None
    assert parsing.parse_bounds("garbage") is None


def test_center_of():
    assert parsing.center_of([0, 0, 100, 200]) == [50, 100]
    assert parsing.center_of(None) is None


# ---- tree parsing ----------------------------------------------------------
def test_parse_ui_tree_extracts_schema_keys(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    assert len(nodes) > 0
    n = nodes[0]
    for key in ("text", "content-desc", "resource-id", "class", "bounds",
                "center", "clickable"):
        assert key in n


def test_parse_ui_tree_computes_center(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    url = parsing.find_node_by_label(nodes, "Server URL")
    assert url["bounds"] == [48, 300, 1032, 420]
    assert url["center"] == [540, 360]


def test_parse_ui_tree_handles_leading_garbage():
    xml = "UI hierchary dumped to: /sdcard/x\n<hierarchy><node text='a' bounds='[0,0][2,2]' class='X'/></hierarchy>"
    nodes = parsing.parse_ui_tree(xml)
    assert nodes[0]["text"] == "a"
    assert nodes[0]["center"] == [1, 1]


def test_parse_ui_tree_empty():
    assert parsing.parse_ui_tree("") == []
    assert parsing.parse_ui_tree("   ") == []


# ---- compact view ----------------------------------------------------------
def test_compact_only_keeps_labeled_nodes(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    compact = parsing.compact_nodes(nodes)
    # Every compact entry must have text or content-desc.
    assert all(c["text"] or c["content-desc"] for c in compact)
    # Layout containers with no label are dropped.
    assert len(compact) < len(nodes)


# ---- node finding ----------------------------------------------------------
def test_find_node_by_label_substring(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    # 'Username' should match 'Username (optional)' content-desc.
    n = parsing.find_node_by_label(nodes, "Username")
    assert n is not None
    assert "Username" in n["content-desc"]


def test_find_node_clickable_only(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    # The 'Settings' title TextView is not clickable; the nav tab is.
    n = parsing.find_node_by_label(nodes, "Settings", clickable_only=True)
    assert n["clickable"] is True
    assert n["content-desc"] == "Settings"


def test_find_node_not_found(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    assert parsing.find_node_by_label(nodes, "Nonexistent") is None


def test_find_node_prefer_clickable_picks_clickable_tab(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    # The fixture has a non-clickable 'Settings' title (first) and a clickable
    # nav tab. prefer_clickable must return the tab, not the title.
    n = parsing.find_node_by_label(nodes, "Settings", prefer_clickable=True)
    assert n["clickable"] is True
    assert n["center"] == [900, 2270]


def test_find_node_prefer_clickable_falls_back_when_none_clickable():
    # No clickable match exists -> fall back to the first match (Compose case
    # where the label sits on a non-clickable TextView).
    nodes = [
        {"text": "Settings", "content-desc": "", "clickable": False,
         "center": [10, 20], "class": "TextView"},
        {"text": "Other", "content-desc": "", "clickable": True,
         "center": [99, 99], "class": "View"},
    ]
    n = parsing.find_node_by_label(nodes, "Settings", prefer_clickable=True)
    assert n["center"] == [10, 20]


def test_find_field_prefers_edittext(settings_xml):
    nodes = parsing.parse_ui_tree(settings_xml)
    field = parsing.find_field_by_label(nodes, "Server URL")
    assert "EditText" in field["class"]


def test_send_button_found_in_chat(chat_xml):
    nodes = parsing.parse_ui_tree(chat_xml)
    send = parsing.find_node_by_label(nodes, "Send")
    assert send is not None
    # Pitfall #2: we resolve Send's real center from the tree, not a guess.
    assert send["center"] == [980, 1920]


def test_find_send_button_prefers_enabled():
    # The Send button is disabled while the box is empty; tapping it does
    # nothing. find_send_button must skip a disabled match for an enabled one.
    nodes = [
        {"text": "", "content-desc": "Send", "class": "Button",
         "clickable": True, "enabled": False, "center": [10, 10]},
        {"text": "", "content-desc": "Send", "class": "Button",
         "clickable": True, "enabled": True, "center": [20, 20]},
    ]
    assert parsing.find_send_button(nodes)["center"] == [20, 20]


def test_find_send_button_falls_back_to_first_when_none_enabled():
    nodes = [
        {"text": "", "content-desc": "Send", "class": "Button",
         "clickable": True, "enabled": False, "center": [10, 10]},
    ]
    assert parsing.find_send_button(nodes)["center"] == [10, 10]


def test_find_send_button_none_when_absent(chat_xml):
    nodes = [{"text": "hi", "content-desc": "", "class": "TextView",
              "enabled": True, "center": [1, 1]}]
    assert parsing.find_send_button(nodes) is None


def test_find_send_button_ignores_placeholder_textview():
    # Real-device regression: an empty session shows the placeholder
    # "No messages yet. Send a message to start." (a non-clickable TextView)
    # which sorts before the real Send button. find_send_button must skip it
    # and return the actual Button (content-desc exactly 'Send').
    nodes = [
        {"text": "No messages yet. Send a message to start.",
         "content-desc": "", "class": "android.widget.TextView",
         "clickable": False, "enabled": True, "center": [540, 1472]},
        {"text": "", "content-desc": "Send", "class": "android.widget.Button",
         "clickable": True, "enabled": True, "center": [976, 1966]},
    ]
    send = parsing.find_send_button(nodes)
    assert send["center"] == [976, 1966]
    assert send["class"].endswith("Button")


# ---- input escaping (pitfall #1) ------------------------------------------
def test_escape_spaces_to_percent_s():
    assert parsing.escape_input_text("hello world") == "hello%sworld"


def test_escape_multiple_spaces():
    assert parsing.escape_input_text("a b c") == "a%sb%sc"


def test_escape_shell_metachars():
    out = parsing.escape_input_text("a&b<c>d|e")
    assert out == "a\\&b\\<c\\>d\\|e"


def test_escape_no_special_unchanged():
    assert parsing.escape_input_text("plaintext123") == "plaintext123"


def test_escape_quotes_and_parens():
    out = parsing.escape_input_text("say (hi) 'now'")
    assert "%s" in out  # spaces handled
    assert "\\(" in out and "\\)" in out
    assert "\\'" in out


# ---- plan generation -------------------------------------------------------
def test_plan_configure_server_order():
    plan = parsing.plan_configure_server("http://h:1", "u", "p")
    ops = [s["op"] for s in plan]
    # Pitfall #3: a *conditional* keyboard dismiss precedes the Settings tab tap
    # (never a blind BACK, which would exit the app on the keyboard-less root).
    assert ops[0] == "dismiss-keyboard"
    assert ops[1] == "tap-label" and plan[1]["label"] == "Settings"
    # No blind BACK key-press anywhere in the plan.
    assert not any(s["op"] == "key" and s.get("key") == "back" for s in plan)
    # URL field is cleared before typing.
    assert "clear-field" in ops
    # Test Connection then Save in that order, with a wait between.
    test_idx = next(i for i, s in enumerate(plan)
                    if s["op"] == "tap-label" and s["label"] == "Test Connection")
    save_idx = next(i for i, s in enumerate(plan)
                    if s["op"] == "tap-label" and s["label"] == "Save")
    assert test_idx < save_idx
    # The async connection test is awaited by polling for the 'Connected'
    # result (not a brittle fixed sleep) before Save.
    waits = plan[test_idx:save_idx]
    assert any(s["op"] == "wait-for-label" and s["label"] == "Connected"
               for s in waits)


def test_plan_configure_server_values_embedded():
    plan = parsing.plan_configure_server("http://host:9", "alice", "secret")
    typed = [s["text"] for s in plan if s["op"] == "type"]
    assert typed == ["http://host:9", "alice", "secret"]


def test_plan_send_prompt_resolves_send_dynamically():
    plan = parsing.plan_send_prompt("do a thing")
    ops = [s["op"] for s in plan]
    assert "tap-label-dynamic" in ops  # Send resolved live (pitfall #2)
    send_step = next(s for s in plan if s["op"] == "tap-label-dynamic")
    assert send_step["label"] == "Send"


def test_plan_send_prompt_dismisses_keyboard_before_send():
    # Real-device bug: dumping to locate Send while the IME is up perturbs
    # focus, so a conditional keyboard dismiss must precede the Send tap.
    plan = parsing.plan_send_prompt("do a thing")
    ops = [s["op"] for s in plan]
    type_idx = ops.index("type")
    dismiss_idx = ops.index("dismiss-keyboard")
    send_idx = ops.index("tap-label-dynamic")
    assert type_idx < dismiss_idx < send_idx
    # No blind BACK key-press in the plan (dismiss-keyboard is conditional).
    assert not any(s["op"] == "key" and s.get("key") == "back" for s in plan)


def test_plan_send_prompt_verifies_send():
    # The plan must record that we verify the prompt left the input box.
    plan = parsing.plan_send_prompt("hello there")
    verify = next(s for s in plan if s["op"] == "verify-input-cleared")
    assert verify["text"] == "hello there"


# ---- send verification helpers --------------------------------------------
def test_input_field_value_reads_first_edittext(chat_xml):
    nodes = parsing.parse_ui_tree(chat_xml)
    # The chat fixture's EditText has empty text.
    assert parsing.input_field_value(nodes) == ""


def test_prompt_still_in_input_detects_unsent():
    nodes = [
        {"class": "android.widget.EditText", "text": "Read the file AGENTS.md"},
    ]
    assert parsing.prompt_still_in_input(nodes, "Read the file AGENTS.md") is True
    # After send the box empties -> not still there.
    nodes[0]["text"] = ""
    assert parsing.prompt_still_in_input(nodes, "Read the file AGENTS.md") is False


def test_prompt_still_in_input_ignores_whitespace_and_empty_needle():
    nodes = [{"class": "android.widget.EditText", "text": "  hi there  "}]
    assert parsing.prompt_still_in_input(nodes, "hi there") is True
    # Empty/whitespace prompt never counts as "still there".
    assert parsing.prompt_still_in_input(nodes, "   ") is False


# ---- device-state parsers --------------------------------------------------
def test_parse_keyboard_shown_true():
    out = "  mServedView=...\n  mInputShown=true\n  mShowRequested=true\n"
    assert parsing.parse_keyboard_shown(out) is True


def test_parse_keyboard_shown_false():
    out = "  mInputShown=false\n  mShowRequested=false\n"
    assert parsing.parse_keyboard_shown(out) is False


def test_parse_keyboard_shown_absent_defaults_false():
    assert parsing.parse_keyboard_shown("no relevant field here") is False
    assert parsing.parse_keyboard_shown("") is False


def test_parse_keyboard_shown_uses_last_occurrence():
    out = "mInputShown=true\n...later...\nmInputShown=false\n"
    assert parsing.parse_keyboard_shown(out) is False


def test_parse_top_activity_main():
    out = (
        "  ResumedActivity in stack=...\n"
        "  topResumedActivity=ActivityRecord{abc u0 "
        "cn.vectory.ocdroid/.MainActivity t42}\n"
    )
    assert parsing.parse_top_activity(out) == "cn.vectory.ocdroid/.MainActivity"


def test_parse_top_activity_launcher():
    out = ("  topResumedActivity=ActivityRecord{def u0 "
           "com.google.android.apps.nexuslauncher/.NexusLauncherActivity t1}\n")
    assert parsing.parse_top_activity(out) == \
        "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"


def test_parse_top_activity_absent():
    assert parsing.parse_top_activity("nothing here") is None


def test_am_start_extra_args_basic_pairs():
    args = parsing.am_start_extra_args({"a": "1", "b": "2"})
    # Flat argv, dict order preserved, each key/value its own element.
    assert args == ["--es", "a", "1", "--es", "b", "2"]


def test_am_start_extra_args_preserves_special_chars_unescaped():
    # Values are exec argv elements, not a shell string: '@', spaces, ':', '/'
    # must pass through verbatim (no quoting/escaping added).
    args = parsing.am_start_extra_args({
        "test_server_url": "http://host:9090",
        "test_username": "alice",
        "test_password": "p@ss w:rd/x",
    })
    assert args == [
        "--es", "test_server_url", "http://host:9090",
        "--es", "test_username", "alice",
        "--es", "test_password", "p@ss w:rd/x",
    ]


def test_am_start_extra_args_empty():
    assert parsing.am_start_extra_args({}) == []
