import re
import socket
import unittest
from pathlib import Path

from tools.p4 import harness

BUILD_GRADLE = Path(__file__).resolve().parents[3] / "build.gradle"
OPTIONS = Path(__file__).resolve().parents[1] / "game_config" / "options.txt"


class HarnessTest(unittest.TestCase):
    def test_required_free_memory_for_three_games(self):
        self.assertEqual(10.5, harness.required_free_gib(3))

    def test_heap_constant_matches_build_gradle_for_every_p4_run(self):
        text = BUILD_GRADLE.read_text(encoding="utf-8")
        for run in ("clientP4", "clientLoadP4", "clientMpP4", "client2P4", "serverP4"):
            block = re.search(r"\b" + run + r" \{(.*?)\n        \}", text, re.S)
            self.assertIsNotNone(block, run)
            heap = re.search(r"'-Xmx(\d+)G'", block.group(1))
            self.assertIsNotNone(heap, run)
            self.assertEqual(harness.GAME_JVM_HEAP_GIB, int(heap.group(1)), run)

    def test_preflight_stops_on_a_busy_port(self):
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            s.listen(1)
            port = s.getsockname()[1]
            with self.assertRaises(harness.PortBusyError):
                harness.preflight(ports=(port,))
        harness.preflight(ports=(port,))  # closed again: no error

    def test_options_template_keeps_the_game_from_pausing_and_from_onboarding(self):
        lines = OPTIONS.read_text(encoding="utf-8").splitlines()
        self.assertIn("pauseOnLostFocus:false", lines)
        self.assertIn("onboardAccessibility:false", lines)


if __name__ == "__main__":
    unittest.main()
