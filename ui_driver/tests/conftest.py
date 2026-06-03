import os

import pytest

FIXTURE_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def _read(name: str) -> str:
    with open(os.path.join(FIXTURE_DIR, name), encoding="utf-8") as fh:
        return fh.read()


@pytest.fixture
def settings_xml() -> str:
    return _read("settings_screen.xml")


@pytest.fixture
def chat_xml() -> str:
    return _read("chat_screen.xml")
