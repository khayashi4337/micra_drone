"""Plan JSON builders: take the golden hut patch and swap the site (origin, facing)."""
import copy
import json
from pathlib import Path

GOLDEN_DIR = Path("src") / "test" / "resources" / "build" / "golden"
GOLDEN_PATCH = GOLDEN_DIR / "hut.patch.json"
GOLDEN_MANIFEST = GOLDEN_DIR / "hut.manifest.txt"
HUT_PLACEMENT_COUNT = 238
BOUNDS_MARGIN = 5
ROOF_SLACK = 2  # blocks above the gable peak, which stands width/2 above the top floor (measured: a 40-wide hut needed it)


def golden_hash():
    """First line of hut.manifest.txt: 'hash <sha256>'."""
    first = GOLDEN_MANIFEST.read_text(encoding="utf-8").splitlines()[0]
    prefix, value = first.split(" ", 1)
    if prefix != "hash":
        raise ValueError(f"unexpected first line: {first}")
    return value.strip()


def load_golden_patch():
    return json.loads(GOLDEN_PATCH.read_text(encoding="utf-8"))


def hut_patch(origin, facing, palette=None, width=None, depth=None, floors=None):
    """The golden hut moved to `origin`/`facing`. width/depth/floors (optional) resize the `hut` structure node; the site's
    operating box is widened to hold it (local box: margin on every side, floor_height per floor, and the gable's peak)."""
    patch = copy.deepcopy(load_golden_patch())
    for op in patch["ops"]:
        if op["op"] == "set_site":
            op["site"]["origin"] = list(origin)
            op["site"]["facing"] = facing
        elif op["op"] == "set_style" and palette:
            op["style"]["palette"].update(palette)
        elif op["op"] == "add_node" and op["node"]["id"] == "hut":
            params = op["node"]["params"]
            if width is not None:
                params["width"] = width
            if depth is not None:
                params["depth"] = depth
            if floors is not None:
                params["floors"] = floors
    if width is not None or depth is not None or floors is not None:
        hut = next(o["node"] for o in patch["ops"] if o["op"] == "add_node" and o["node"]["id"] == "hut")["params"]
        site = site_of(patch)
        margin = BOUNDS_MARGIN
        peak = max(hut["width"], hut["depth"]) // 2
        site["bounds"] = [-margin, -margin, -margin, hut["width"] + margin,
                          hut["floors"] * hut["floor_height"] + peak + ROOF_SLACK + 2 * margin, hut["depth"] + margin]
    return patch


def site_of(patch):
    for op in patch["ops"]:
        if op["op"] == "set_site":
            return op["site"]
    raise ValueError("patch has no set_site")
