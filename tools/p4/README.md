# tools/p4 — automated real-machine checks for the construction runtime

One command starts the game, builds in a real world, reads every block back, saves evidence and cleans up.
Nobody has to click or look: the owner is not in the confirmation loop.

```
python -m tools.p4.p4_scenarios --all                      # every scenario (run from the repository root)
python -m tools.p4.p4_scenarios --only hut-golden --mode sp
python -m tools.p4.p4_scenarios --all --run-id my-run      # evidence goes to run-evidence/p4/<run-id>/
python -m unittest discover -s tools/p4/tests -t .         # the pure-Python tests (no game)
```

Exit code 1 if any scenario FAILed or was NOT-RUN. **NOT-RUN is never a pass.**

## What it needs

- Windows (a game window gets `WM_CLOSE`; no keyboard or mouse input is ever synthesized).
- Java 21 under `~/.gradle/jdks` (or `P4_JAVA=<java.exe>`), and the devkit checkout at `G:/prj2/micra_drone_devkit`
  (or `P4_DEVKIT_DIR`). The devkit jar is built and copied into `run-p4/{client,client2,server}/mods/` every run.
- Ports 47391, 47392, 47393 and 25565 free. If any is busy the script stops before starting anything
  (it will not drive a game it did not start).
- Multiplayer scenarios (`--mode mp|mp2`) need the owner to create `tools/p4/eula_ack.txt` containing `eula=true`
  once (accepting the EULA is the account holder's consent). Singleplayer scenarios do not need it.

## How a run goes

preflight (ports) → Gradle **once** (`jar` + `dumpP4LaunchInfo`, which depends on the five `prepare*Run` tasks and
writes `build/moddev/run*P4-launch.json`) → devkit build and jar placement → `java` started directly with the
dumped VM args and classpath (no Gradle process stays next to a game) → world → scenarios → logs copied →
`WM_CLOSE` → `cleanup()` in a `finally` (kills only the processes this run started).

Only the devkit jar goes into `mods/`. micradrone itself loads from the dev classpath; a second copy would be a
duplicate mod id.

## Evidence (`run-evidence/p4/<runId>/`)

`summary.json` (per scenario and per completion condition), `prepare.json`, `launch-*.json` (the exact command),
`logs/` (latest.log, console, new crash reports), `cleanup.json`, and one folder per scenario, for example
`hut-golden/compare.json` (238 placements, 0 mismatches), `hut-golden/hut-golden.png`, `hut-here/pending.json`.

## Scenarios

| name | conditions | what it proves |
|---|---|---|
| `devkit-smoke` | – | the client and server devkit endpoints answer with `runId`, `pid`, `gameDir` |
| `hut-golden` | 1, 5 | `sample:hut` → the server's manifest hash equals the golden file → approve → VERIFIED → all 238 placements read back and compared by `compare.py` (an independent Python rule) |
| `hut-here` | 1, 12 | `here` on the grass: terrain cut = 49, approval is refused until `confirmTerraform`, then built and read back |
| `bad-source` | – | a path-escaping plan source is refused with the child-facing Japanese sentence in chat, and the pending request is unchanged |

`PENDING_CONDITIONS` in `p4_scenarios.py` lists the completion conditions that have no scenario yet and the task
that adds them; `tests/test_registry.py` fails if a condition is neither covered nor listed.
