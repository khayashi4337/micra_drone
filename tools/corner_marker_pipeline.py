# Single source of truth for the Corner Marker block's textures: 16x16 pixel art hand-painted from
# the ChatGPT reference sheet (F:\Download\ChatGPT Image 2026年8月28日 21_46_20.png, part ②),
# same palette as controller_block_pipeline.py so the two blocks read as one product family.
# Two states are painted: idle (待機中) and active (信号出力中, brighter/thicker glow) - the active
# set is wired up once the marker gets its redstone POWERED blockstate; until then only the idle
# set is referenced by models/block/corner_marker.json.
#
# Layout is driven by the named constants below; the remaining literals inside the paint
# functions are individual shading pixels (they ARE the drawing, as in controller_block_pipeline.py).
import os

from pixel_png import blank, px, rect, save_png, contact_sheet

W = H = 16

# ---------------- palette (shared with controller_block_pipeline.py) ----------------
HULL     = (45, 172, 168, 255)   # #2DACA8 main teal body
HULL_HI  = (112, 210, 204, 255)  # #70D2CC lit edge
HULL_LO  = (30, 128, 126, 255)   # #1E807E shaded edge
DARK     = (14, 20, 26, 255)     # #0E141A panel black
GLOW     = (110, 240, 255, 255)  # #6EF0FF cyan glow
GLOW_HI  = (190, 252, 255, 255)  # hot core of the active glow
GLOW_DIM = (60, 165, 185, 255)   # dimmer/idle glow
TRIM     = (18, 22, 26, 255)     # #12161A dark base trim
METAL    = (150, 155, 160, 255)  # #969BA0 bolt / name plate
METAL_DK = (90, 95, 100, 255)    # bolt shadow, plate text dots

# ---------------- layout ----------------
BOLT_INSET = 1                        # bolts sit 1px in from each corner
# top face
TOP_PANEL = (2, 2, 12, 12)            # x, y, w, h of the black panel inside the teal rim
BRACKET_IDLE = (5, 8, 4, 1)           # '⌜' corner mark: origin x, y, arm length, thickness
BRACKET_ACTIVE = (4, 7, 6, 2)
# side face
SIDE_TRIM_Y = 13                      # dark base trim from this row down
SIDE_PANEL = (2, 3, 12, 9)
NAME_PLATE = (5, 4, 6, 2)
NAME_PLATE_DOTS_X = (6, 8, 10)        # "text" dots along the plate's second row
LIGHT_STRIP_X, LIGHT_STRIP_W = 3, 10
LIGHT_STRIP_Y_IDLE = 9
LIGHT_STRIP_Y_ACTIVE = 8              # active strip is 2px tall starting here
# bottom face
GRATING_PITCH = 3                     # metal grid line every 3px
CENTER_TILE = (7, 7, 2, 2)


def corner_bolts(im, bottom_row):
    for bx, by in ((BOLT_INSET, BOLT_INSET), (W - 1 - BOLT_INSET, BOLT_INSET),
                   (BOLT_INSET, bottom_row), (W - 1 - BOLT_INSET, bottom_row)):
        px(im, bx, by, METAL)


def teal_rim(im):
    """Teal body with a lit top/left edge and a shaded right edge."""
    rect(im, 0, 0, W, H, HULL)
    rect(im, 0, 0, W, 1, HULL_HI)
    rect(im, 0, 0, 1, H, HULL_HI)
    rect(im, W - 1, 0, 1, H, HULL_LO)


def bracket(im, active):
    """The '⌜' corner mark on the top face, in the lower-left quadrant like the reference."""
    x, y, arm, thick = BRACKET_ACTIVE if active else BRACKET_IDLE
    rect(im, x, y, arm, thick, GLOW)       # horizontal bar
    rect(im, x, y, thick, arm, GLOW)       # vertical bar
    if active:
        px(im, x, y, GLOW_HI)                          # hot corner
        rect(im, x + arm, y, 1, thick, GLOW_DIM)       # glow tails past each bar end
        rect(im, x, y + arm, thick, 1, GLOW_DIM)


def top(active):
    im = blank(W, H)
    teal_rim(im)
    rect(im, 0, H - 1, W, 1, HULL_LO)
    rect(im, *TOP_PANEL, DARK)
    corner_bolts(im, H - 1 - BOLT_INSET)
    bracket(im, active)
    return im


def side(active):
    im = blank(W, H)
    teal_rim(im)
    rect(im, 0, SIDE_TRIM_Y, W, H - SIDE_TRIM_Y, TRIM)
    rect(im, 0, SIDE_TRIM_Y - 1, W, 1, HULL_LO)
    corner_bolts(im, SIDE_TRIM_Y - 2)
    rect(im, *SIDE_PANEL, DARK)
    # name plate (the marker is anvil-renamable / numbered)
    rect(im, *NAME_PLATE, METAL)
    for dx in NAME_PLATE_DOTS_X:
        px(im, dx, NAME_PLATE[1] + 1, METAL_DK)
    # light strip
    if active:
        rect(im, LIGHT_STRIP_X, LIGHT_STRIP_Y_ACTIVE, LIGHT_STRIP_W, 2, GLOW)
        rect(im, LIGHT_STRIP_X + 1, LIGHT_STRIP_Y_ACTIVE, LIGHT_STRIP_W - 2, 1, GLOW_HI)
        rect(im, LIGHT_STRIP_X, LIGHT_STRIP_Y_ACTIVE + 2, LIGHT_STRIP_W, 1, GLOW_DIM)
    else:
        rect(im, LIGHT_STRIP_X, LIGHT_STRIP_Y_IDLE, LIGHT_STRIP_W, 1, GLOW_DIM)
        rect(im, LIGHT_STRIP_X + 2, LIGHT_STRIP_Y_IDLE, LIGHT_STRIP_W - 4, 1, GLOW)
    return im


def bottom(active):
    im = blank(W, H)
    rect(im, 0, 0, W, H, HULL_LO)
    rect(im, 1, 1, W - 2, H - 2, DARK)
    for i in range(GRATING_PITCH, W - 2, GRATING_PITCH):
        rect(im, i, 1, 1, H - 2, METAL_DK)
        rect(im, 1, i, W - 2, 1, METAL_DK)
    corner_bolts(im, H - 1 - BOLT_INSET)
    # center tile: plain when idle, lit when signalling
    rect(im, *CENTER_TILE, GLOW if active else METAL_DK)
    if active:
        px(im, CENTER_TILE[0], CENTER_TILE[1], GLOW_HI)
    return im


FACES = {
    "corner_marker_top": lambda: top(False),
    "corner_marker_side": lambda: side(False),
    "corner_marker_bottom": lambda: bottom(False),
    "corner_marker_active_top": lambda: top(True),
    "corner_marker_active_side": lambda: side(True),
    "corner_marker_active_bottom": lambda: bottom(True),
}


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    tex_dir = os.path.join(here, "..", "src", "main", "resources", "assets", "micradrone", "textures", "block")
    for name, fn in FACES.items():
        save_png(os.path.join(tex_dir, name + ".png"), fn())

    preview_dir = os.environ.get("PREVIEW_DIR", here)
    order = ["corner_marker_top", "corner_marker_side", "corner_marker_bottom",
             "corner_marker_active_top", "corner_marker_active_side", "corner_marker_active_bottom"]
    sheet = contact_sheet([FACES[n]() for n in order], 3, 10)
    save_png(os.path.join(preview_dir, "preview_corner_marker.png"), sheet)
    print("wrote 6 corner marker textures + preview_corner_marker.png (row 1 idle, row 2 active)")


if __name__ == "__main__":
    main()
