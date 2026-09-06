# Single source of truth for every pumpkin texture in the mod: 16x16 pixel art hand-painted from
# the ChatGPT reference sheet (F:\Download\ChatGPT Image 2026年8月29日 02_29_31.png - growth
# stages, ripe pumpkin, rotten pumpkin, giant pumpkin tiles). Twelve textures come out of this:
#
#   pumpkin_crop_stage0/1/2   cross-plane sprites (vanilla block/crop model, transparent background)
#   pumpkin_crop_top/side     the ripe pumpkin, a full opaque cube like vanilla's pumpkin
#   rotten_pumpkin_top/side   the same cube gone bad (dead pumpkin, see PumpkinCropBlock)
#   giant_pumpkin_nw/ne/sw/se/n/e/s/w/center   TOP faces of a fused patch, one per
#                              GiantPumpkinBlock POSITION (see GIANT_TILES below)
#   giant_pumpkin_side         the patch's outer side face (inner faces are never visible)
#
# Sprites are written as string art (one character per pixel, see STAGE_* and the KEY table) so the
# drawing itself is readable; the block faces are painted with rect/px like the other pipelines.
# Layout is driven by the named constants below; the remaining literals inside the paint functions
# are individual shading pixels (they ARE the drawing, as in corner_marker_pipeline.py).
import math
import os

from pixel_png import blank, px, rect, save_png, contact_sheet

W = H = 16

# ---------------- palette ----------------
OR       = (240, 132, 20, 255)   # pumpkin skin
OR_HI    = (255, 180, 66, 255)   # lit rib crest
OR_DK    = (196, 92, 10, 255)    # rib groove
OUTLINE  = (112, 52, 12, 255)    # dark brown outline / deep shadow
STEM     = (118, 68, 28, 255)
STEM_HI  = (162, 102, 50, 255)
LEAF     = (96, 168, 44, 255)
LEAF_HI  = (150, 214, 82, 255)
LEAF_DK  = (52, 108, 32, 255)
VINE     = (66, 132, 40, 255)
GREEN_P  = (110, 178, 56, 255)   # unripe pumpkin body
GREEN_DK = (64, 122, 38, 255)
GREEN_HI = (162, 218, 98, 255)
ROT      = (150, 140, 92, 255)   # rotten skin
ROT_HI   = (180, 170, 120, 255)
ROT_DK   = (108, 98, 62, 255)
ROT_OUT  = (66, 58, 38, 255)
MOLD     = (56, 50, 42, 255)
ROT_STEM = (96, 70, 40, 255)
WILT     = (98, 112, 56, 255)    # wilted leaf
WILT_DK  = (62, 74, 36, 255)
FLY      = (236, 236, 236, 255)
FLY_DK   = (28, 28, 28, 255)
SOIL     = (88, 60, 36, 255)     # farmland seen past the giant's rounded corner
SOIL_DK  = (66, 44, 26, 255)

# ---------------- layout ----------------
STEM_BOX = (6, 6, 4, 4)                 # ripe top face: stem blob at the centre
RIB_HALF = 6                            # ripe top face: radial rib length from the stem's edge
SIDE_RIB_XS = (1, 4, 7, 10, 13)         # ripe side face: rib groove columns
SIDE_CREST_XS = (2, 5, 8)               # ripe side face: lit crests (light from the upper left)
SIDE_CREST_ROWS = (2, 7)                # rows the crests span (y, height)
LEAF_CLUMP_Y = 11                       # side faces: leaf clumps start on this row
GIANT_RIB_XS = (3, 8, 13)               # giant top tiles: groove columns shared by every tile so they tile seamlessly
GIANT_CREST_XS = (1, 6, 11)
GIANT_CORNER_CENTRE = 11                # giant corner tile: the rounded outline is a circle around (11, 11) ...
GIANT_CORNER_RADIUS = 11.0              # ... of this radius; outside it is soil
GIANT_SIDE_RIB_XS = (2, 7, 12)          # giant side face: wide ribs
GIANT_SIDE_CREST_XS = (4, 9, 14)
GIANT_SIDE_LEAF_XS = (0, 6, 12)         # giant side face: leaf clumps along the bottom

# One character per pixel for the string-art sprites below ('.' = transparent).
KEY = {
    "V": VINE, "L": LEAF, "H": LEAF_HI, "D": LEAF_DK,
    "G": GREEN_P, "g": GREEN_DK, "h": GREEN_HI,
    "W": WILT, "w": WILT_DK,
}

STAGE_SPROUT = [
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    ".....DD...DD....",
    "....DLHD.DHLD...",
    ".....DLLDLLD....",
    "......DDVDD.....",
    ".......V........",
    ".......V........",
]

STAGE_VINE = [
    "................",
    "........VV......",
    ".......V..V.....",
    ".......V........",
    "..DD...VV..DD...",
    ".DLHD..V..DHLD..",
    ".DLLLD.V.DLLLD..",
    "..DLLDDV.DLLD...",
    "...DDD.VDDD.....",
    "......VV........",
    "......V.........",
    ".....VV.........",
    ".....V..........",
    "......V.........",
    ".......V........",
    ".......V........",
]

STAGE_SMALL_PUMPKIN = [
    "........V.......",
    ".......VV.V.....",
    ".......V..VV....",
    "..DD...V....V...",
    ".DLHD..V...DD...",
    ".DLLD.VV..DLHD..",
    "..DDDDV...DLLD..",
    "......Vg...DD...",
    "....gGGhGg......",
    "...gGhGGGGg.....",
    "...gGGgGGGg.....",
    "...gGGgGGGg.....",
    "...gGGgGGGg.....",
    "....gGgGGg......",
    ".....gggg.......",
    "................",
]

# A 4x5 leaf clump for the bottom corners of the side faces (flipped for the right-hand corner).
LEAF_CLUMP = [
    "..DD",
    ".DLL",
    "DLHL",
    "DLLD",
    ".DD.",
]


def stamp(im, x0, y0, rows, key=KEY, flip=False):
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == ".":
                continue
            xx = x0 + (len(row) - 1 - dx if flip else dx)
            px(im, xx, y0 + dy, key[ch])


def sprite(rows):
    im = blank(W, H)
    stamp(im, 0, 0, rows)
    return im


def rounded_corners(im, c):
    """Three dark pixels in each corner so a full-cube face still reads as a round pumpkin."""
    for (x, y) in ((0, 0), (1, 0), (0, 1), (W - 1, 0), (W - 2, 0), (W - 1, 1),
                   (0, H - 1), (1, H - 1), (0, H - 2), (W - 1, H - 1), (W - 2, H - 1), (W - 1, H - 2)):
        px(im, x, y, c)


def fly(im, x, y):
    """A 1px body with a white wing either side - the reference's 'obviously dead' cue."""
    px(im, x, y, FLY_DK)
    px(im, x - 1, y - 1, FLY)
    px(im, x + 1, y - 1, FLY)


# ---------------- ripe / rotten cube faces ----------------

def pumpkin_top(body, hi, dk, out, stem, stem_hi):
    im = blank(W, H)
    rect(im, 0, 0, W, H, body)
    sx, sy, sw, sh = STEM_BOX
    # eight radial ribs out from the stem's edge: N/S/E/W and the diagonals
    for i in range(RIB_HALF):
        px(im, sx + 1, sy - 1 - i, dk)                 # N
        px(im, sx + sw - 2, sy + sh + i, dk)           # S
        px(im, sx - 1 - i, sy + sh - 2, dk)            # W
        px(im, sx + sw + i, sy + 1, dk)                # E
        px(im, sx - 1 - i, sy - 1 - i, dk)             # NW
        px(im, sx + sw + i, sy - 1 - i, dk)            # NE
        px(im, sx - 1 - i, sy + sh + i, dk)            # SW
        px(im, sx + sw + i, sy + sh + i, dk)           # SE
    # lit crests on the upper-left segments
    for (x, y) in ((5, 1), (5, 2), (6, 2), (1, 5), (2, 5), (2, 6), (9, 1), (10, 2)):
        px(im, x, y, hi)
    rounded_corners(im, out)
    rect(im, sx, sy, sw, sh, stem)
    px(im, sx, sy, stem_hi)
    px(im, sx + 1, sy, stem_hi)
    px(im, sx, sy + 1, stem_hi)
    return im


def pumpkin_side(body, hi, dk, out, stem, leaf_key):
    im = blank(W, H)
    rect(im, 0, 0, W, H, body)
    for x in SIDE_RIB_XS:
        rect(im, x, 1, 1, H - 2, dk)
    cy, ch = SIDE_CREST_ROWS
    for x in SIDE_CREST_XS:
        rect(im, x, cy, 1, ch, hi)
    rect(im, 0, 0, W, 1, dk)                # shoulder
    rect(im, 0, H - 1, W, 1, dk)            # base shade
    rounded_corners(im, out)
    rect(im, 7, 0, 2, 1, stem)              # stem base peeking over the shoulder
    stamp(im, 0, LEAF_CLUMP_Y, LEAF_CLUMP, leaf_key)
    stamp(im, W - 4, LEAF_CLUMP_Y, LEAF_CLUMP, leaf_key, flip=True)
    return im


def ripe_top():
    return pumpkin_top(OR, OR_HI, OR_DK, OUTLINE, STEM, STEM_HI)


def ripe_side():
    return pumpkin_side(OR, OR_HI, OR_DK, OUTLINE, STEM, KEY)


WILT_KEY = {"D": WILT_DK, "L": WILT, "H": WILT}


def rotten_top():
    im = pumpkin_top(ROT, ROT_HI, ROT_DK, ROT_OUT, ROT_STEM, ROT_STEM)
    # the stem droops off to the right instead of standing up
    for (x, y) in ((10, 5), (11, 4), (12, 4)):
        px(im, x, y, ROT_STEM)
    for (x, y) in ((2, 3), (11, 2), (3, 11), (12, 12)):   # mould spots
        rect(im, x, y, 2, 2, MOLD)
    fly(im, 2, 1)
    fly(im, 13, 3)
    return im


def rotten_side():
    im = pumpkin_side(ROT, ROT_HI, ROT_DK, ROT_OUT, ROT_STEM, WILT_KEY)
    px(im, 9, 0, ROT_STEM)                                  # drooping stem
    # X eyes
    for (x, y) in ((4, 5), (6, 5), (5, 6), (4, 7), (6, 7), (9, 5), (11, 5), (10, 6), (9, 7), (11, 7)):
        px(im, x, y, MOLD)
    # frown
    for (x, y) in ((5, 11), (6, 10), (7, 10), (8, 10), (9, 11)):
        px(im, x, y, MOLD)
    rect(im, 12, 3, 2, 2, MOLD)                             # mould spots
    rect(im, 2, 9, 2, 2, MOLD)
    fly(im, 2, 1)
    fly(im, 13, 2)
    return im


# ---------------- giant pumpkin ----------------
#
# Nine TOP tiles, one per GiantPumpkinBlock POSITION, all from the same function: the flags say
# which of the tile's sides lie on the patch's outer boundary. Two adjacent flags make a rounded
# corner, one flag a straight rim, none the plain center. They are separate textures rather than
# one corner/edge texture turned by the blockstate because the ribs must run north-south across the
# whole patch (see the reference sheet) - a rotated edge tile would have its ribs running east-west.

GIANT_TILES = {  # texture suffix -> (west, north, east, south) boundary flags; keyed by POSITION order
    "nw": (True, True, False, False),
    "ne": (False, True, True, False),
    "sw": (True, False, False, True),
    "se": (False, False, True, True),
    "w": (True, False, False, False),
    "e": (False, False, True, False),
    "n": (False, True, False, False),
    "s": (False, False, False, True),
    "center": (False, False, False, False),
}

RIM_LEAF_OFFSETS = ((5, LEAF_DK), (6, LEAF_DK), (11, LEAF_DK), (12, LEAF))   # leaves peeking over a rim
SOIL_GRAIN = ((0, 3), (3, 0), (2, 2), (5, 1), (1, 5))                          # NW corner, mirrored for the others
SOIL_LEAF = ((1, 1, LEAF), (2, 1, LEAF), (1, 2, LEAF), (2, 2, LEAF_DK))


def giant_tile(west, north, east, south):
    im = blank(W, H)
    c = GIANT_CORNER_CENTRE
    r = GIANT_CORNER_RADIUS
    far = W - 1 - c   # the same circle centre, measured from the east/south side
    for y in range(H):
        for x in range(W):
            ex = (max(0, c - x) if west else 0) + (max(0, x - far) if east else 0)
            ey = (max(0, c - y) if north else 0) + (max(0, y - far) if south else 0)
            d = math.hypot(ex, ey)
            if d > r + 0.5:
                colour = SOIL
            elif d > r - 0.5:
                colour = OUTLINE
            elif d > r - 1.5:
                colour = OR_DK
            elif x in GIANT_RIB_XS:
                colour = OR_DK
            elif x in GIANT_CREST_XS:
                colour = OR_HI
            else:
                colour = OR
            px(im, x, y, colour)
    # leaves peeking over each straight rim
    for (i, colour) in RIM_LEAF_OFFSETS:
        if north:
            px(im, i, 0, colour)
        if south:
            px(im, W - 1 - i, H - 1, colour)
        if west:
            px(im, 0, W - 1 - i, colour)
        if east:
            px(im, W - 1, i, colour)
    # soil grain and one small leaf in the rounded-off corner
    if (west or east) and (north or south):
        def mirror(x, y):
            return (x if west else W - 1 - x, y if north else H - 1 - y)
        for (x, y) in SOIL_GRAIN:
            px(im, *mirror(x, y), SOIL_DK)
        for (x, y, colour) in SOIL_LEAF:
            px(im, *mirror(x, y), colour)
    return im


def giant_side():
    im = blank(W, H)
    rect(im, 0, 0, W, H, OR)
    for x in GIANT_SIDE_RIB_XS:
        rect(im, x, 1, 1, H - 1, OR_DK)
    for x in GIANT_SIDE_CREST_XS:
        rect(im, x, 1, 1, 8, OR_HI)
    rect(im, 0, 0, W, 1, OUTLINE)
    for x in GIANT_SIDE_LEAF_XS:
        stamp(im, x, LEAF_CLUMP_Y, LEAF_CLUMP, KEY, flip=(x % 2 == 0 and x > 0))
    return im


FACES = {
    "pumpkin_crop_stage0": lambda: sprite(STAGE_SPROUT),
    "pumpkin_crop_stage1": lambda: sprite(STAGE_VINE),
    "pumpkin_crop_stage2": lambda: sprite(STAGE_SMALL_PUMPKIN),
    "pumpkin_crop_top": ripe_top,
    "pumpkin_crop_side": ripe_side,
    "rotten_pumpkin_top": rotten_top,
    "rotten_pumpkin_side": rotten_side,
    "giant_pumpkin_side": giant_side,
}
for _suffix, _flags in GIANT_TILES.items():
    FACES["giant_pumpkin_" + _suffix] = (lambda flags: lambda: giant_tile(*flags))(_flags)


# ---------------- preview helpers ----------------

def compose_giant_top(n):
    """An n x n fused patch seen from above, tiled the way LiveFarmBlockAccess#applyGiantPumpkinPatch places the 9 positions."""
    tiles = {suffix: giant_tile(*flags) for suffix, flags in GIANT_TILES.items()}
    out = blank(W * n, H * n)
    for j in range(n):
        for i in range(n):
            west, east = i == 0, i == n - 1
            north, south = j == 0, j == n - 1
            which = ("n" if north else "s" if south else "") + ("w" if west else "e" if east else "")
            tile = tiles[which or "center"] if which in tiles else tiles["center"]
            for y in range(H):
                for x in range(W):
                    out[j * H + y][i * W + x] = tile[y][x]
    return out


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    tex_dir = os.path.join(here, "..", "src", "main", "resources", "assets", "micradrone", "textures", "block")
    for name, fn in FACES.items():
        save_png(os.path.join(tex_dir, name + ".png"), fn())

    preview_dir = os.environ.get("PREVIEW_DIR", here)
    order = list(FACES.keys())
    save_png(os.path.join(preview_dir, "preview_pumpkin_faces.png"),
             contact_sheet([FACES[n]() for n in order], 4, 10, background=(120, 120, 120, 255)))
    save_png(os.path.join(preview_dir, "preview_giant_3x3.png"), contact_sheet([compose_giant_top(3)], 1, 8))
    save_png(os.path.join(preview_dir, "preview_giant_5x5.png"), contact_sheet([compose_giant_top(5)], 1, 6))
    print("wrote %d pumpkin textures + preview_pumpkin_faces.png (stages 0-2, ripe top/side, rotten"
          " top/side, giant side, then the 9 giant top tiles nw ne sw se w e n s center),"
          " preview_giant_3x3.png, preview_giant_5x5.png" % len(FACES))


if __name__ == "__main__":
    main()
