"""Independent read-back check: compares the server's manifest with the blocks read from the world.

Pure Python on purpose. It does NOT reuse micradrone's SnapshotDiff / BlockStates, so a bug in those cannot
hide itself: the rule per VerifyMode is written down again here.
"""
from dataclasses import dataclass

VERIFY_EXACT = "EXACT"
VERIFY_STATE_SUBSET = "STATE_SUBSET"
VERIFY_BLOCK_ONLY = "BLOCK_ONLY"
VERIFY_ASSEMBLED_AWAY = "ASSEMBLED_AWAY"


@dataclass(frozen=True)
class Mismatch:
    index: int
    pos: tuple
    expected: str
    observed: str
    reason: str


def parse_state(text):
    """'id[k=v,k2=v2]' -> (id, {k: v}). A bare id gives an empty dict."""
    if "[" not in text:
        return text, {}
    block_id, rest = text.split("[", 1)
    body = rest.rstrip("]")
    props = {}
    if body:
        for pair in body.split(","):
            key, value = pair.split("=", 1)
            props[key] = value
    return block_id, props


def _describe(block):
    props = block.get("props") or {}
    if not props:
        return block["id"]
    return block["id"] + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


def compare(manifest_tree, readback):
    """readback: {(x, y, z): 'id[k=v]'}. Returns a list of Mismatch (empty = the world matches).

    If a position appears in several placements, only the LAST one counts (later placements overwrite earlier
    ones, exactly like the world does).
    """
    last = {}
    for placement in manifest_tree["placements"]:
        last[tuple(placement["pos"])] = placement
    out = []
    for pos, placement in last.items():
        verify = placement.get("verify", VERIFY_EXACT)
        if verify == VERIFY_ASSEMBLED_AWAY:
            continue
        expected = placement["block"]
        expected_text = _describe(expected)
        observed_text = readback.get(pos)
        index = placement.get("index", -1)
        if observed_text is None:
            out.append(Mismatch(index, pos, expected_text, "", "unread"))
            continue
        observed_id, observed_props = parse_state(observed_text)
        if observed_id != expected["id"]:
            out.append(Mismatch(index, pos, expected_text, observed_text, "block"))
            continue
        if verify == VERIFY_BLOCK_ONLY:
            continue
        expected_props = expected.get("props") or {}
        for key in sorted(expected_props):
            if observed_props.get(key) != expected_props[key]:
                out.append(Mismatch(index, pos, expected_text, observed_text, f"state:{key}"))
                break
        else:
            if verify == VERIFY_EXACT and set(observed_props) - set(expected_props):
                out.append(Mismatch(index, pos, expected_text, observed_text, "state:extra"))
    return out
