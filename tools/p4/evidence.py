"""Evidence folder for one run: run-evidence/p4/<runId>/ with summary.json."""
import json
import shutil
from pathlib import Path

PASS = "PASS"
FAIL = "FAIL"
NOT_RUN = "NOT-RUN"
# every scenario of the condition passed, but part of the condition text has no scenario yet
PARTIAL = "PARTIAL"
PENDING = "PENDING"  # no scenario exists yet: a condition that is not built is never silently absent
STATUSES = (PASS, FAIL, NOT_RUN)
EVIDENCE_ROOT = Path("run-evidence") / "p4"


class RunFolder:
    def __init__(self, run_id, root=EVIDENCE_ROOT, partial=None, pending=None):
        self.run_id = run_id
        self.pending = dict(pending or {})
        self.partial = dict(partial or {})
        self.path = Path(root) / run_id
        self.path.mkdir(parents=True, exist_ok=True)
        self.scenarios = {}

    def write_json(self, name, obj):
        target = self.path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(obj, ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        return target

    def write_text(self, name, text):
        target = self.path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
        return target

    def add_file(self, src, name):
        target = self.path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, target)
        return target

    def record(self, scenario, conditions, status, reason, files=()):
        if status not in STATUSES:
            raise ValueError(f"status must be one of {STATUSES}: {status}")
        if status != PASS and not reason:
            raise ValueError(f"{status} needs a reason (never skip silently): {scenario}")
        self.scenarios[scenario] = {"conditions": sorted(conditions), "status": status,
                                    "reason": reason, "files": list(files)}
        self.write_summary()

    def condition_states(self):
        """A condition is PASS only if every scenario carrying it PASSed; FAIL beats NOT-RUN."""
        states = {}
        for entry in self.scenarios.values():
            for condition in entry["conditions"]:
                states.setdefault(condition, []).append(entry["status"])
        out = {}
        for condition, seen in states.items():
            if FAIL in seen:
                out[condition] = FAIL
            elif NOT_RUN in seen:
                out[condition] = NOT_RUN
            elif condition in self.partial:
                out[condition] = PARTIAL
            else:
                out[condition] = PASS
        for condition in self.pending:
            out.setdefault(condition, PENDING)
        return out

    def write_summary(self):
        summary = {"runId": self.run_id, "scenarios": self.scenarios,
                   "partialConditions": {str(k): v for k, v in sorted(self.partial.items())},
                   "pendingConditions": {str(k): v for k, v in sorted(self.pending.items())},
                   "conditions": {str(k): v for k, v in sorted(self.condition_states().items())}}
        self.write_json("summary.json", summary)
        return summary

    def all_passed(self):
        return bool(self.scenarios) and all(s["status"] == PASS for s in self.scenarios.values())
