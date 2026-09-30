"""Plan JSON builders: take the golden hut patch and swap the site (origin, facing)."""
import copy
import json
from pathlib import Path

GOLDEN_DIR = Path("src") / "test" / "resources" / "build" / "golden"
GOLDEN_PATCH = GOLDEN_DIR / "hut.patch.json"
GOLDEN_MANIFEST = GOLDEN_DIR / "hut.manifest.txt"
HUT_PLACEMENT_COUNT = 238


def golden_hash():
    """First line of hut.manifest.txt: 'hash <sha256>'."""
    first = GOLDEN_MANIFEST.read_text(encoding="utf-8").splitlines()[0]
    prefix, value = first.split(" ", 1)
    if prefix != "hash":
        raise ValueError(f"unexpected first line: {first}")
    return value.strip()


def load_golden_patch():
    return json.loads(GOLDEN_PATCH.read_text(encoding="utf-8"))


def hut_patch(origin, facing, palette=None):
    patch = copy.deepcopy(load_golden_patch())
    for op in patch["ops"]:
        if op["op"] == "set_site":
            op["site"]["origin"] = list(origin)
            op["site"]["facing"] = facing
        elif op["op"] == "set_style" and palette:
            op["style"]["palette"].update(palette)
    return patch


def site_of(patch):
    for op in patch["ops"]:
        if op["op"] == "set_site":
            return op["site"]
    raise ValueError("patch has no set_site")
