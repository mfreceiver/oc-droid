"""Verify adb failures are passed through verbatim (hard requirement), not
wrapped in a generic message."""

import json

from ui_driver.adb import Adb, AdbError
from ui_driver.cli import main


def test_adberror_to_dict_carries_raw_fields():
    e = AdbError(["shell", "input", "tap", "1", "2"], 1, "out", "device offline")
    d = e.to_dict()
    assert d["error"] == "adb_command_failed"
    assert d["exit_code"] == 1
    assert d["stderr"] == "device offline"
    assert d["adb_args"] == ["shell", "input", "tap", "1", "2"]


def test_cli_emits_adb_error_json(monkeypatch, capsys):
    # Force Adb._run to raise an AdbError as a real failure would.
    def boom(self, args, check=True, timeout=30):
        raise AdbError(args, 1, "", "more than one device/emulator")

    monkeypatch.setattr(Adb, "_run", boom)
    monkeypatch.setattr(Adb, "_auto_emulator", lambda self: "emulator-5554")
    rc = main(["tap-xy", "10", "20"])
    assert rc == 2
    out = json.loads(capsys.readouterr().out)
    assert out["error"] == "adb_command_failed"
    assert out["exit_code"] == 1
    assert "more than one device" in out["stderr"]
