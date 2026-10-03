"""Switches the stub AI's behaviour for the length of a `with` block (see tools/p4/stub_bin/stub_claude.py: MODE_FILE)."""
from contextlib import contextmanager
from pathlib import Path

MODE_FILE = Path(__file__).resolve().parent / "stub_bin" / "mode.txt"


@contextmanager
def stub_mode(mode):
    MODE_FILE.write_text(mode, encoding="utf-8")
    try:
        yield
    finally:
        MODE_FILE.unlink(missing_ok=True)
