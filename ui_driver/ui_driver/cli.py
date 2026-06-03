"""argparse CLI. `python -m ui_driver <command>`.

Every command prints a JSON object to stdout. adb failures are caught and
emitted as JSON error objects (with exit code 2) carrying adb's raw exit code
and stderr, rather than a generic message.
"""

from __future__ import annotations

import argparse
import json
import sys

from .adb import Adb, AdbError
from .driver import Driver


def _add_screenshot(p: argparse.ArgumentParser) -> None:
    p.add_argument("--screenshot", metavar="PATH",
                   help="also save a PNG screenshot to PATH and include it in JSON")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ui_driver",
        description="Deterministic adb/uiautomator driver for the opencode "
                    "Android client. Each mutating command returns the "
                    "post-action UI tree as JSON.")
    parser.add_argument("--serial", help="device serial (overrides ANDROID_SERIAL "
                                         "/ auto emulator selection)")
    parser.add_argument("--adb", dest="adb_path", help="path to adb binary")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("tree", help="dump current UI tree (observe only)")
    p.add_argument("--scroll-find", metavar="LABEL",
                   help="scroll down until LABEL appears, then dump")
    _add_screenshot(p)

    p = sub.add_parser("tap-xy", help="tap a coordinate")
    p.add_argument("x", type=int)
    p.add_argument("y", type=int)
    _add_screenshot(p)

    p = sub.add_parser("tap-text", help="tap first node containing LABEL in text/desc")
    p.add_argument("label")
    _add_screenshot(p)

    p = sub.add_parser("type-text", help="type into the focused field (spaces->%%s)")
    p.add_argument("text")
    _add_screenshot(p)

    p = sub.add_parser("clear-field", help="clear the focused input field")
    _add_screenshot(p)

    p = sub.add_parser("key", help="press a navigation key")
    p.add_argument("name", choices=["back", "home", "enter"])
    _add_screenshot(p)

    p = sub.add_parser("launch", help="cold-start MainActivity")
    _add_screenshot(p)

    sub.add_parser("clear-data", help="pm clear: reset app to clean state")

    p = sub.add_parser("scroll-to-text", help="scroll down until LABEL is visible")
    p.add_argument("label")
    _add_screenshot(p)

    p = sub.add_parser("configure-server",
                       help="Settings -> fill URL/user/pass -> Test -> Save")
    p.add_argument("--url", required=True)
    p.add_argument("--username", required=True)
    p.add_argument("--password", required=True)
    _add_screenshot(p)

    p = sub.add_parser("send-prompt", help="type prompt into chat input and Send")
    p.add_argument("text")
    _add_screenshot(p)

    p = sub.add_parser("select-model", help="open model selector and pick NAME")
    p.add_argument("name")
    _add_screenshot(p)

    return parser


def _dispatch(args: argparse.Namespace, driver: Driver) -> dict:
    cmd = args.command
    if cmd == "tree":
        return driver.tree(screenshot=args.screenshot, scroll_find=args.scroll_find)
    if cmd == "tap-xy":
        return driver.tap_xy(args.x, args.y, screenshot=args.screenshot)
    if cmd == "tap-text":
        return driver.tap_text(args.label, screenshot=args.screenshot)
    if cmd == "type-text":
        return driver.type_text(args.text, screenshot=args.screenshot)
    if cmd == "clear-field":
        return driver.clear_field(screenshot=args.screenshot)
    if cmd == "key":
        return driver.key(args.name, screenshot=args.screenshot)
    if cmd == "launch":
        return driver.launch(screenshot=args.screenshot)
    if cmd == "clear-data":
        return driver.clear_data()
    if cmd == "scroll-to-text":
        return driver.scroll_to_text(args.label, screenshot=args.screenshot)
    if cmd == "configure-server":
        return driver.configure_server(args.url, args.username, args.password,
                                       screenshot=args.screenshot)
    if cmd == "send-prompt":
        return driver.send_prompt(args.text, screenshot=args.screenshot)
    if cmd == "select-model":
        return driver.select_model(args.name, screenshot=args.screenshot)
    raise SystemExit(f"unhandled command: {cmd}")


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    adb = Adb(serial=args.serial, adb_path=args.adb_path)
    driver = Driver(adb)
    try:
        result = _dispatch(args, driver)
    except AdbError as e:
        # Pass adb's exit code / stderr straight through; do not wrap.
        json.dump(e.to_dict(), sys.stdout, indent=2)
        sys.stdout.write("\n")
        return 2
    json.dump(result, sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    return 0 if result.get("ok", True) else 1


if __name__ == "__main__":
    raise SystemExit(main())
