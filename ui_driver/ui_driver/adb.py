"""IO layer: a thin, mockable wrapper around the adb binary.

Everything that actually touches the emulator lives here so the pure logic in
parsing.py can be tested in isolation. The class is intentionally small; tests
mock `_run` (or the whole class) to avoid needing a device.
"""

from __future__ import annotations

import os
import subprocess
import time
from dataclasses import dataclass
from typing import Optional

from . import parsing


class AdbError(Exception):
    """Raised when an adb invocation fails.

    Carries the raw exit code / stderr so the CLI can pass them straight
    through to the caller. We deliberately do NOT collapse adb failures into a
    generic message (hard requirement): over-exposing detail beats hiding it.
    """

    def __init__(self, args: list[str], returncode: int, stdout: str, stderr: str):
        # Note: Exception.__init__ overwrites .args, so store under adb_args.
        self.adb_args = args
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
        super().__init__(
            f"adb {' '.join(args)} failed (exit {returncode}): {stderr.strip()}"
        )

    def to_dict(self) -> dict:
        return {
            "error": "adb_command_failed",
            "adb_args": self.adb_args,
            "exit_code": self.returncode,
            "stdout": self.stdout,
            "stderr": self.stderr,
        }


@dataclass
class CompletedAdb:
    args: list[str]
    returncode: int
    stdout: str
    stderr: str


def default_adb_path() -> str:
    """Resolve the adb binary: $ANDROID_HOME/platform-tools/adb, with
    ANDROID_HOME defaulting to ~/Library/Android/sdk. Falls back to bare 'adb'
    on PATH if that file is absent."""
    android_home = os.environ.get("ANDROID_HOME") or os.path.join(
        os.path.expanduser("~"), "Library", "Android", "sdk"
    )
    candidate = os.path.join(android_home, "platform-tools", "adb")
    if os.path.exists(candidate):
        return candidate
    return "adb"


class Adb:
    """Thin adb wrapper bound to a single device serial.

    Device selection precedence (so we never hit 'more than one
    device/emulator' when a physical phone is also plugged in):
      1. explicit serial arg / --serial
      2. ANDROID_SERIAL env var
      3. first 'emulator-*' from `adb devices`
    """

    PACKAGE = "cn.vectory.ocdroid"
    ACTIVITY = ".MainActivity"

    def __init__(self, serial: Optional[str] = None, adb_path: Optional[str] = None):
        self.adb_path = adb_path or default_adb_path()
        self.serial = serial or os.environ.get("ANDROID_SERIAL") or self._auto_emulator()

    # ---- low level ------------------------------------------------------
    def _run(self, args: list[str], check: bool = True,
             timeout: int = 30) -> CompletedAdb:
        """Run `adb [-s serial] <args>`. Raises AdbError on non-zero exit when
        check=True. This is the single seam tests mock."""
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += args
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        result = CompletedAdb(args=args, returncode=proc.returncode,
                              stdout=proc.stdout, stderr=proc.stderr)
        if check and proc.returncode != 0:
            raise AdbError(args, proc.returncode, proc.stdout, proc.stderr)
        return result

    def _auto_emulator(self) -> Optional[str]:
        """Pick the first emulator-* serial. Returns None if none found (then
        adb runs with no -s; if multiple non-emulator devices exist it will
        error, which we pass through)."""
        try:
            out = self._run_no_serial(["devices"]).stdout
        except Exception:
            return None
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device" and parts[0].startswith("emulator-"):
                return parts[0]
        return None

    def _run_no_serial(self, args: list[str], timeout: int = 30) -> CompletedAdb:
        cmd = [self.adb_path] + args
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return CompletedAdb(args=args, returncode=proc.returncode,
                            stdout=proc.stdout, stderr=proc.stderr)

    # ---- shell helpers --------------------------------------------------
    def shell(self, args: list[str], check: bool = True, timeout: int = 30) -> CompletedAdb:
        return self._run(["shell"] + args, check=check, timeout=timeout)

    # ---- observation ----------------------------------------------------
    def dump_ui_xml(self) -> str:
        """uiautomator dump to a device file, then cat it back.

        We dump to /sdcard then read, rather than relying on `dump /dev/tty`,
        because the latter interleaves a status line with the XML on some
        API levels. Reading the file back is the robust path.
        """
        self.shell(["uiautomator", "dump", "/sdcard/uidump.xml"], timeout=30)
        return self.shell(["cat", "/sdcard/uidump.xml"]).stdout

    def is_keyboard_shown(self) -> bool:
        """Whether the soft keyboard (IME) is currently up.

        Reads `dumpsys input_method` and parses `mInputShown=...`. The driver
        uses this so it only presses BACK to collapse the keyboard when one is
        actually shown; on the app root (no IME) a blind BACK would exit the
        app to the launcher.
        """
        out = self.shell(["dumpsys", "input_method"]).stdout
        return parsing.parse_keyboard_shown(out)

    def wait_for_keyboard_hidden(self, timeout: float = 2.0,
                                 interval: float = 0.2) -> bool:
        """Poll until the soft keyboard is no longer shown, or timeout.

        After pressing BACK to collapse the IME, the dismiss + relayout is
        animated: is_keyboard_shown() can still report True for a moment, and a
        dump taken then captures the Send button at its keyboard-UP position.
        Tapping that stale coordinate misses (Send has since slid down), which
        is the intermittent send-prompt failure. We wait for the IME to settle
        before dumping for Send. Returns whether the keyboard is down."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.is_keyboard_shown():
                return True
            time.sleep(interval)
        return not self.is_keyboard_shown()

    def top_activity(self) -> Optional[str]:
        """The current foreground activity component ('pkg/.Activity'), or None.

        Parsed from `dumpsys activity activities` (topResumedActivity)."""
        out = self.shell(["dumpsys", "activity", "activities"]).stdout
        return parsing.parse_top_activity(out)

    def wait_for_foreground(self, component: Optional[str] = None,
                            timeout: float = 5.0, interval: float = 0.25) -> bool:
        """Poll until `component` is the foreground activity, or timeout.

        Defaults to this app's MainActivity. Returns True once seen, False on
        timeout. Used after `am start` (and before observing) so we don't dump
        the tree while the launcher / previous screen is still on top."""
        target = component or f"{self.PACKAGE}/{self.ACTIVITY}"
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.top_activity() == target:
                return True
            time.sleep(interval)
        return self.top_activity() == target

    def screenshot(self, local_path: str) -> str:
        """Capture a PNG to local_path via exec-out screencap."""
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        cmd += ["exec-out", "screencap", "-p"]
        proc = subprocess.run(cmd, capture_output=True, timeout=30)
        if proc.returncode != 0:
            raise AdbError(["exec-out", "screencap", "-p"], proc.returncode,
                           "", proc.stderr.decode("utf-8", "replace"))
        with open(local_path, "wb") as fh:
            fh.write(proc.stdout)
        return local_path

    # ---- actions --------------------------------------------------------
    def tap(self, x: int, y: int) -> None:
        self.shell(["input", "tap", str(x), str(y)])

    def input_text_escaped(self, escaped: str) -> None:
        """Send already-escaped text (caller used escape_input_text)."""
        self.shell(["input", "text", escaped])

    def keyevent(self, code: str) -> None:
        self.shell(["input", "keyevent", code])

    def swipe(self, x1: int, y1: int, x2: int, y2: int, ms: int = 300) -> None:
        self.shell(["input", "swipe", str(x1), str(y1), str(x2), str(y2), str(ms)])

    def clear_field(self, repeats: int = 80) -> None:
        """Empty the focused input: jump to end, then delete many times.

        We KEYCODE_MOVE_END first so we delete leftward from the tail; the
        repeat count is a generous upper bound (server URLs / passwords are
        short enough that 80 backspaces clears them)."""
        self.keyevent("KEYCODE_MOVE_END")
        for _ in range(repeats):
            self.keyevent("KEYCODE_DEL")

    def launch(self, string_extras: Optional[dict] = None) -> None:
        """Cold-start MainActivity, then settle.

        `am start` returns before the activity is actually on top, so we poll
        the foreground activity until MainActivity is resumed (or ~5s elapses)
        before returning. Without this, an immediate dump can catch the launcher
        or the previous screen.

        `string_extras` is an optional {key: value} mapping turned into
        `--es key value` pairs. Each key/value is passed to `self.shell` as a
        separate argv element, so subprocess hands them to adb verbatim (no
        device-shell word-splitting) — values containing spaces, '@', etc. are
        delivered intact. `am start` is the only mutating launch path, so we
        keep the wait-for-foreground here rather than re-implementing it.
        """
        self.shell(["am", "start", "-n", f"{self.PACKAGE}/{self.ACTIVITY}",
                    *parsing.am_start_extra_args(string_extras or {})])
        self.wait_for_foreground()

    def clear_data(self) -> None:
        """pm clear: reset the app to a pristine state."""
        self.shell(["pm", "clear", self.PACKAGE])

    @staticmethod
    def sleep(seconds: float) -> None:
        time.sleep(seconds)
