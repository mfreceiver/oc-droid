"""Integration test skeleton. Skipped by default. These hit a REAL emulator
and require:
  - a running emulator (auto-selected as emulator-*, or set ANDROID_SERIAL)
  - the opencode client installed
  - env var UI_DRIVER_RUN_INTEGRATION=1 to opt in
Run with:  UI_DRIVER_RUN_INTEGRATION=1 pytest -m integration tests/test_integration.py
The user runs these manually against a live emulator; CI / unit runs skip them.
"""

import os

import pytest

from ui_driver.adb import Adb
from ui_driver.driver import Driver

_RUN = os.environ.get("UI_DRIVER_RUN_INTEGRATION") == "1"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(not _RUN, reason="set UI_DRIVER_RUN_INTEGRATION=1 + emulator"),
]


@pytest.fixture
def driver():
    return Driver(Adb())


def test_launch_and_dump(driver):
    out = driver.launch()
    assert out["ok"] is True
    assert out["node_count"] > 0


def test_configure_server_roundtrip(driver):
    url = os.environ.get("OPENCODE_URL", "http://10.0.2.2:4096")
    user = os.environ.get("OPENCODE_USER", "")
    pw = os.environ.get("OPENCODE_PASS", "")
    out = driver.configure_server(url, user, pw)
    assert out["ok"] is True


def test_send_prompt_roundtrip(driver):
    out = driver.send_prompt("say hello")
    assert out["ok"] is True
