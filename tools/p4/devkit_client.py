"""Tiny HTTP client for the devkit APIs (loopback only). Every answer carries the game's runId."""
import json
import time
import urllib.request

LOOPBACK = "127.0.0.1"
REQUEST_TIMEOUT_S = 30
POLL_INTERVAL_S = 1.0


class DevkitError(Exception):
    pass


class ForeignGameError(Exception):
    """The API answered, but from a game this run did not start (or from a different runId)."""


class Devkit:
    def __init__(self, port, run_id):
        self.port = port
        self.run_id = run_id

    def _url(self, path):
        return f"http://{LOOPBACK}:{self.port}{path}"

    def _decode(self, raw, path):
        result = json.loads(raw.decode("utf-8"))
        if result.get("runId") != self.run_id:
            raise ForeignGameError(f"{path} on port {self.port} answered runId={result.get('runId')!r}, "
                                   f"expected {self.run_id!r}")
        if "error" in result:
            raise DevkitError(f"{path}: {result['error']}")
        return result

    def post(self, path, body=None):
        data = json.dumps(body or {}, ensure_ascii=False).encode("utf-8")  # the devkit reads raw UTF-8; its JSON reader drops the backslash of XXXX escapes
        req = urllib.request.Request(self._url(path), data=data, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_S) as resp:
            return self._decode(resp.read(), path)

    def get(self, path):
        with urllib.request.urlopen(self._url(path), timeout=REQUEST_TIMEOUT_S) as resp:
            return self._decode(resp.read(), path)

    def poll(self, path, body, until, timeout, interval=POLL_INTERVAL_S):
        """POST until until(result) is truthy; returns (result, [every result seen with its time])."""
        deadline = time.monotonic() + timeout
        seen = []
        while True:
            result = self.post(path, body)
            seen.append({"t": round(time.time(), 3), "result": result})
            if until(result):
                return result, seen
            if time.monotonic() > deadline:
                raise TimeoutError(f"{path} did not reach the wanted state in {timeout}s; last={result}")
            time.sleep(interval)
