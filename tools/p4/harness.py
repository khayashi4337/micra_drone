"""Starts, waits for and closes the games of one P4 run. Nothing here sends OS input to a game window.

Flow: preflight (ports free) -> prepare (Gradle ONCE, before any game exists) -> start_* (java is started
directly, one game at a time) -> ... -> collect_logs -> close_client (WM_CLOSE) / stop_server -> cleanup.
The Gradle run never overlaps a game: two Gradles in one checkout are not allowed.
"""
import ctypes
import filecmp
import glob
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from ctypes import wintypes
from pathlib import Path

from tools.p4 import devkit_client

REPO = Path(__file__).resolve().parents[2]
DEVKIT_DIR = Path(os.environ.get("P4_DEVKIT_DIR", "G:/prj2/micra_drone_devkit"))
RUN_DIR = REPO / "run-p4"
GAME_CONFIG_DIR = Path(__file__).resolve().parent / "game_config"
MODDEV_DIR = REPO / "build" / "moddev"

CLIENT_API_PORT = 47391
SERVER_API_PORT = 47392
CLIENT2_API_PORT = 47393
GAME_PORT = 25565
WATCHED_PORTS = (CLIENT_API_PORT, SERVER_API_PORT, CLIENT2_API_PORT, GAME_PORT)

API_UP_TIMEOUT_S = 300
IN_WORLD_TIMEOUT_S = 300
JOB_TIMEOUT_S = 600
CLOSE_TIMEOUT_S = 120
POLL_INTERVAL_S = 1.0
GAME_JVM_HEAP_GIB = 3
GAME_JVM_NATIVE_GIB = 0.5
GRADLE_TIMEOUT_S = 1500

# kind -> (Gradle run name, game folder, client API port)
KINDS = {
    "sp": ("clientP4", "client", CLIENT_API_PORT),
    "sp-load": ("clientLoadP4", "client", CLIENT_API_PORT),
    "mp": ("clientMpP4", "client", CLIENT_API_PORT),
    "mp2": ("client2P4", "client2", CLIENT2_API_PORT),
    "server": ("serverP4", "server", SERVER_API_PORT),
}
# dumpP4LaunchInfo (build.gradle) depends on the five prepare*Run tasks and writes build/moddev/<run>-launch.json
PREPARE_TASKS = ["dumpP4LaunchInfo"]
DEV_LAUNCH_MAIN = "net.neoforged.devlaunch.Main"
WM_CLOSE = 0x0010


class PortBusyError(Exception):
    pass


class NoWindowError(Exception):
    pass


class GameCrashedError(Exception):
    pass


def required_free_gib(n_games):
    return n_games * (GAME_JVM_HEAP_GIB + GAME_JVM_NATIVE_GIB)


def port_in_use(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=0.5):
            return True
    except OSError:
        return False


def preflight(ports=WATCHED_PORTS):
    busy = [p for p in ports if port_in_use(p)]
    if busy:
        raise PortBusyError(f"ports already listening: {busy}. Another game (or an old run) holds them; "
                            "this script never touches a game it did not start.")


def find_java21():
    override = os.environ.get("P4_JAVA")
    if override:
        return Path(override)
    hits = sorted(glob.glob(str(Path.home() / ".gradle" / "jdks" / "*21*" / "bin" / "java.exe")))
    if not hits:
        raise FileNotFoundError("no Java 21 under ~/.gradle/jdks; set P4_JAVA to a Java 21 java.exe")
    return Path(hits[0])


def free_physical_gib():
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command", "(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory"],
        capture_output=True, text=True, timeout=60).stdout.strip()
    return int(out) / (1024 * 1024)


def top_processes_by_memory(n=10):
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         "Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First %d Name,Id,"
         "@{n='MiB';e={[int]($_.WorkingSet64/1MB)}} | ConvertTo-Json" % n],
        capture_output=True, text=True, timeout=60).stdout
    return json.loads(out) if out.strip() else []


def process_working_set_mib(pid):
    out = subprocess.run(
        ["powershell", "-NoProfile", "-Command",
         f"(Get-CimInstance Win32_Process -Filter 'ProcessId={pid}').WorkingSetSize"],
        capture_output=True, text=True, timeout=60).stdout.strip()
    return round(int(out) / (1024 * 1024)) if out else None


def _visible_windows_of(pid):
    user32 = ctypes.windll.user32
    found = []
    enum_proc = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)

    def visit(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd):
            found.append(hwnd)
        return True

    user32.EnumWindows(enum_proc(visit), 0)
    return found


class Game:
    def __init__(self, run_id, folder):
        self.run_id = run_id
        self.folder = folder  # RunFolder (evidence)
        self.children = []  # every Popen this run started
        self.procs = {}  # kind -> Popen
        self.commands = {}
        self.java = None
        self.crash_reports_before = {}

    # ---- preparation (Gradle runs here, once, and is gone before any game starts) ----
    def prepare(self):
        gradle = [str(REPO / "gradlew.bat"), "--no-daemon", f"-Pp4RunId={self.run_id}", "jar", *PREPARE_TASKS,
                  "--console=plain"]
        result = subprocess.run(gradle, cwd=REPO, capture_output=True, text=True, timeout=GRADLE_TIMEOUT_S)
        (self.folder.path / "prepare-gradle.log").write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
        if result.returncode != 0:
            raise RuntimeError("Gradle prepare failed; see prepare-gradle.log")
        self.build_devkit()
        jar = self.devkit_jar()
        for name in ("client", "client2", "server"):
            self.place_mods(name, jar)
            self.place_config(name)
        self.java = find_java21()
        prepared = {kind: {"vmArgs": str(self._args_file(run, "RunVmArgs")),
                           "programArgs": str(self._args_file(run, "RunProgramArgs"))}
                    for kind, (run, _, _) in KINDS.items()}
        self.folder.write_json("prepare.json", {
            "java": str(self.java), "gradleCommand": gradle, "moddevFiles": sorted(p.name for p in MODDEV_DIR.iterdir()),
            "argFiles": prepared, "devkitJar": str(jar), "modsPlacement": "devkit jar only; micradrone loads from "
            "the dev classpath (a second micradrone jar in mods/ would be a duplicate mod id)"})

    def build_devkit(self):
        result = subprocess.run([str(DEVKIT_DIR / "gradlew.bat"), "--no-daemon", "build", "--console=plain"],
                                cwd=DEVKIT_DIR, capture_output=True, text=True, timeout=GRADLE_TIMEOUT_S)
        (self.folder.path / "devkit-build.log").write_text(result.stdout + "\n" + result.stderr, encoding="utf-8")
        if result.returncode != 0:
            raise RuntimeError("devkit build failed; see devkit-build.log")

    def devkit_jar(self):
        jars = [p for p in (DEVKIT_DIR / "build" / "libs").glob("micradrone_devkit-*.jar")
                if "sources" not in p.name]
        if len(jars) != 1:
            raise RuntimeError(f"expected exactly one devkit jar, found {jars}")
        return jars[0]

    def place_mods(self, name, jar):
        mods = RUN_DIR / name / "mods"
        mods.mkdir(parents=True, exist_ok=True)
        for old in mods.glob("micradrone_devkit-*.jar"):
            old.unlink()
        target = mods / jar.name
        shutil.copyfile(jar, target)
        if not filecmp.cmp(jar, target, shallow=False):
            raise RuntimeError(f"{target} is not identical to {jar}")

    def place_config(self, name):
        base = RUN_DIR / name
        (base / "config").mkdir(parents=True, exist_ok=True)
        # NeoForge 21.1 keeps the mod's SERVER config in <gameDir>/config here (measured: run-p4/client/config/
        # micradrone-server.toml), so the raised claim cap for test worlds is written there before every start
        shutil.copyfile(GAME_CONFIG_DIR / "micradrone-server.toml", base / "config" / "micradrone-server.toml")
        if name != "server":
            shutil.copyfile(GAME_CONFIG_DIR / "options.txt", base / "options.txt")
            shutil.copyfile(GAME_CONFIG_DIR / "neoforge-client.toml", base / "config" / "neoforge-client.toml")
        else:
            shutil.copyfile(GAME_CONFIG_DIR.parent / "server.properties", base / "server.properties")
            ack = GAME_CONFIG_DIR.parent / "eula_ack.txt"
            if ack.exists():
                shutil.copyfile(ack, base / "eula.txt")

    def eula_present(self):
        return (GAME_CONFIG_DIR.parent / "eula_ack.txt").exists()

    def clear_worlds(self):
        for path in (RUN_DIR / "client" / "saves" / "p4-auto", RUN_DIR / "server" / "world"):
            if path.exists():
                shutil.rmtree(path)

    @staticmethod
    def _args_file(run, suffix):
        return MODDEV_DIR / f"{run}{suffix}.txt"

    # ---- start / wait / close ----
    def _start(self, kind):
        run, folder, _ = KINDS[kind]
        if kind in self.procs and self.procs[kind].poll() is None:
            raise RuntimeError(f"{kind} is already running")
        cwd = RUN_DIR / folder
        cwd.mkdir(parents=True, exist_ok=True)
        self.crash_reports_before[folder] = self._crash_reports(folder)
        launch = json.loads((MODDEV_DIR / f"run{run[0].upper()}{run[1:]}-launch.json").read_text(encoding="utf-8"))
        cmd = [str(self.java), *launch["jvmArgs"], "-cp", os.pathsep.join(launch["runtimeClasspath"]),
               DEV_LAUNCH_MAIN, f"@{self._args_file(run, 'RunProgramArgs')}"]
        self.commands[kind] = cmd
        log = open(cwd / f"console-{kind}.log", "wb")
        proc = subprocess.Popen(cmd, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
        self.children.append(proc)
        self.procs[kind] = proc
        self.folder.write_json(f"launch-{kind}.json", {"cmd": cmd, "cwd": str(cwd), "pid": proc.pid})
        return proc

    def start_client(self, kind):
        return self._start(kind)

    def start_server(self):
        return self._start("server")

    def wait_api(self, kind, timeout=API_UP_TIMEOUT_S):
        _, _, port = KINDS[kind]
        client = devkit_client.Devkit(port, self.run_id)
        proc = self.procs[kind]
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise GameCrashedError(f"{kind} exited with code {proc.returncode} before its API answered")
            try:
                return client.get("/state") if kind != "server" else client.post("/server/state")
            except devkit_client.ForeignGameError:
                raise
            except Exception as e:  # not up yet
                last = e
                time.sleep(POLL_INTERVAL_S)
        raise TimeoutError(f"{kind} API not up in {timeout}s; last error: {last}")

    def wait_screen(self, client, name, timeout=API_UP_TIMEOUT_S):
        return client.poll("/state", {}, lambda r: r.get("screen") == name, timeout)[0]

    def wait_in_world(self, client, timeout=IN_WORLD_TIMEOUT_S):
        return client.poll("/state", {}, lambda r: r.get("inWorld") is True, timeout)[0]

    def close_client(self, kind):
        proc = self.procs[kind]
        if proc.poll() is not None:
            return
        windows = _visible_windows_of(proc.pid)
        if not windows:
            raise NoWindowError(f"{kind} (pid {proc.pid}) has no visible window to send WM_CLOSE to")
        for hwnd in windows:
            ctypes.windll.user32.PostMessageW(hwnd, WM_CLOSE, 0, 0)
        try:
            proc.wait(timeout=CLOSE_TIMEOUT_S)
        except subprocess.TimeoutExpired:
            raise TimeoutError(f"{kind} still running {CLOSE_TIMEOUT_S}s after WM_CLOSE")

    def stop_server(self):
        proc = self.procs.get("server")
        if proc is None or proc.poll() is not None:
            return
        devkit_client.Devkit(SERVER_API_PORT, self.run_id).post("/server/stop")
        proc.wait(timeout=CLOSE_TIMEOUT_S)

    # ---- evidence and cleanup ----
    def _crash_reports(self, folder):
        return {p.name for p in (RUN_DIR / folder / "crash-reports").glob("*.txt")}

    def memory_snapshot(self):
        return {str(kind): process_working_set_mib(p.pid) for kind, p in self.procs.items() if p.poll() is None}

    def collect_logs(self, target="logs"):
        for kind, (_, folder, _) in KINDS.items():
            latest = RUN_DIR / folder / "logs" / "latest.log"
            if latest.exists():
                self.folder.add_file(latest, f"{target}/{folder}-latest.log")
            console = RUN_DIR / folder / f"console-{kind}.log"
            if console.exists():
                self.folder.add_file(console, f"{target}/{folder}-console-{kind}.log")
            before = self.crash_reports_before.get(folder, set())
            for crash in (RUN_DIR / folder / "crash-reports").glob("*.txt"):
                if crash.name not in before:
                    self.folder.add_file(crash, f"{target}/{folder}-{crash.name}")

    def cleanup(self):
        report = []
        for proc in self.children:
            alive = proc.poll() is None
            report.append({"pid": proc.pid, "aliveAtCleanup": alive})
            if alive:
                subprocess.run(["taskkill", "/T", "/F", "/PID", str(proc.pid)], capture_output=True)
        self.folder.write_json("cleanup.json", report)
        return report


def main_guard():
    if sys.platform != "win32":
        raise SystemExit("tools.p4 drives Windows game windows (WM_CLOSE); run it on Windows")
