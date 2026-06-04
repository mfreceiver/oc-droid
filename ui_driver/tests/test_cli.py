"""CLI-level tests: argument parsing for inject-credentials and the JSON
`ok`-field normalization. The Driver is monkeypatched so no emulator is touched
and we assert purely on dispatch + output contract."""

import json

from ui_driver import cli
from ui_driver.cli import build_parser, main


def test_inject_credentials_parses_required_args():
    args = build_parser().parse_args([
        "inject-credentials",
        "--url", "http://host:9090",
        "--username", "alice",
        "--password", "p@ss w:rd",
    ])
    assert args.command == "inject-credentials"
    assert args.url == "http://host:9090"
    assert args.username == "alice"
    assert args.password == "p@ss w:rd"


def test_inject_credentials_dispatches_to_driver(monkeypatch, capsys):
    captured = {}

    class FakeDriver:
        def __init__(self, *_a, **_k):
            pass

        def inject_credentials(self, url, username, password, screenshot=None):
            captured.update(url=url, username=username, password=password)
            return {"ok": True, "nodes": [], "compact": []}

    monkeypatch.setattr(cli, "Driver", FakeDriver)
    monkeypatch.setattr(cli, "Adb", lambda *a, **k: object())
    rc = main([
        "inject-credentials",
        "--url", "http://host:9090",
        "--username", "alice",
        "--password", "p@ss w:rd",
    ])
    assert rc == 0
    assert captured == {
        "url": "http://host:9090",
        "username": "alice",
        "password": "p@ss w:rd",
    }
    out = json.loads(capsys.readouterr().out)
    assert out["ok"] is True


def test_ok_field_normalized_to_false_when_missing(monkeypatch, capsys):
    # A command whose result dict omits `ok` must serialize as a concrete
    # `false` (never null) and exit non-zero, so callers' boolean checks judge
    # the failure correctly.
    class FakeDriver:
        def __init__(self, *_a, **_k):
            pass

        def send_prompt(self, text, screenshot=None):
            return {"error": "send_not_confirmed", "sent": False, "ok": None}

    monkeypatch.setattr(cli, "Driver", FakeDriver)
    monkeypatch.setattr(cli, "Adb", lambda *a, **k: object())
    rc = main(["send-prompt", "hi"])
    assert rc == 1
    out = json.loads(capsys.readouterr().out)
    assert out["ok"] is False
