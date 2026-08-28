# Single source of truth for the Region Pointer item icon: 32x32 pixel art (same resolution as the
# Script Scroll icon) hand-painted from the ChatGPT reference (F:\Download\ChatGPT Image
# 2026年8月28日 21_46_20.png, part ①) - a survey wand: dark wrapped shaft from bottom-left to a
# compass-dial head at top-right, cyan reticle (four corner brackets + centre cross) in the dial,
# teal collar under the head, teal cap with a cyan gem at the grip end. Same palette as the blocks.
#
# Layout is driven by the named constants below; only the per-pixel shading offsets inside the
# paint functions are literal (they ARE the drawing, as in controller_block_pipeline.py).
import math
import os

from pixel_png import blank, px, save_png, upscale

W = H = 32

HULL     = (45, 172, 168, 255)
HULL_HI  = (112, 210, 204, 255)
HULL_LO  = (30, 128, 126, 255)
DARK     = (14, 20, 26, 255)
GLOW     = (110, 240, 255, 255)
GLOW_DIM = (60, 165, 185, 255)
TRIM     = (18, 22, 26, 255)
TRIM_HI  = (40, 46, 52, 255)     # lit edge of the dark shaft so it reads as round
METAL    = (150, 155, 160, 255)
METAL_DK = (90, 95, 100, 255)

# ---- layout: the shaft runs at 45 degrees from GRIP up-right to HEAD, one (+1,-1) step per t ----
GRIP = (4, 27)                    # grip end (bottom-left), t = 0
HEAD = (22, 9)                    # head/dial centre, t = SHAFT_STEPS
SHAFT_STEPS = HEAD[0] - GRIP[0]   # 18
HEAD_R_OUTER = 6.9                # teal ring, outer radius
HEAD_R_INNER = 4.9                # dark dial radius; leaves a 1px gap between reticle and ring
RING_SHADE_THRESHOLD = 4          # (dx+dy) beyond +-this is the shaded / lit part of the ring
RING_BOLTS = ((5, -4), (-6, 0), (1, 6))  # bolt positions relative to the head centre
RETICLE_BRACKET_OFFSET = 3        # corner brackets sit at (+-3, +-3) from the dial centre
WRAP_PERIOD = 4                   # a metal wrap ring every 4 shaft steps ...
WRAP_BAND_PHASES = (1, 2)         # ... 2 steps wide
COLLAR_START, COLLAR_STEPS = 11, 3   # teal collar just before the ring (ring reaches ~5 steps out)
GRIP_CAP_START, GRIP_CAP_STEPS = -2, 5
GEM_STEP = -1                     # cyan gem on the grip cap
# Pixels forming a ~3px-wide cross-section of the (diagonal) shaft around a centre pixel.
CROSS_SECTION = ((0, 0), (1, 0), (0, 1), (-1, 0), (0, -1), (1, -1), (-1, 1))


def along_shaft(t):
    return GRIP[0] + t, GRIP[1] - t


def paint_shaft(im):
    for t in range(SHAFT_STEPS):
        cx, cy = along_shaft(t)
        band = t % WRAP_PERIOD in WRAP_BAND_PHASES
        core = METAL if band else TRIM
        edge = METAL_DK if band else TRIM_HI
        # 3px-wide diagonal: the centre pixel plus one to each side (perpendicular to the axis)
        px(im, cx, cy, core)
        px(im, cx + 1, cy, edge if not band else METAL)
        px(im, cx, cy + 1, edge if not band else METAL_DK)
        px(im, cx - 1, cy, edge)
        px(im, cx, cy - 1, TRIM_HI if not band else METAL)


def paint_teal_section(im, start, steps):
    for t in range(start, start + steps):
        cx, cy = along_shaft(t)
        for dx, dy in CROSS_SECTION:
            px(im, cx + dx, cy + dy, HULL)


def paint_collar(im):
    paint_teal_section(im, COLLAR_START, COLLAR_STEPS)
    for t in range(COLLAR_START, COLLAR_START + COLLAR_STEPS):
        cx, cy = along_shaft(t)
        px(im, cx - 1, cy - 1, HULL_HI)
        px(im, cx + 1, cy + 1, HULL_LO)


def paint_grip(im):
    paint_teal_section(im, GRIP_CAP_START, GRIP_CAP_STEPS)
    gx, gy = along_shaft(GEM_STEP)
    px(im, gx, gy, GLOW)
    px(im, gx - 1, gy, GLOW_DIM)
    px(im, gx, gy + 1, GLOW_DIM)
    px(im, gx - 2, gy + 1, HULL_LO)
    px(im, gx - 1, gy + 2, HULL_LO)


def paint_head(im):
    hx, hy = HEAD
    for y in range(H):
        for x in range(W):
            d = math.hypot(x - hx, y - hy)
            if d < HEAD_R_INNER:
                px(im, x, y, DARK)
            elif d < HEAD_R_OUTER:
                # lit on the upper-left, shaded on the lower-right, like a bevelled ring
                shade = (x - hx) + (y - hy)
                px(im, x, y, HULL_HI if shade < -RING_SHADE_THRESHOLD
                   else HULL_LO if shade > RING_SHADE_THRESHOLD else HULL)
    for dx, dy in RING_BOLTS:
        px(im, hx + dx, hy + dy, METAL)
        px(im, hx + dx + 1, hy + dy, METAL_DK)
    # reticle: four corner brackets + centre cross
    for sx, sy in ((-1, -1), (1, -1), (-1, 1), (1, 1)):
        ax, ay = hx + sx * RETICLE_BRACKET_OFFSET, hy + sy * RETICLE_BRACKET_OFFSET
        px(im, ax, ay, GLOW)
        px(im, ax - sx, ay, GLOW)
        px(im, ax, ay - sy, GLOW)
    px(im, hx, hy, GLOW)
    for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        px(im, hx + dx, hy + dy, GLOW_DIM)


def icon():
    im = blank(W, H)
    paint_shaft(im)
    paint_grip(im)
    paint_collar(im)
    paint_head(im)
    return im


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    tex_dir = os.path.join(here, "..", "src", "main", "resources", "assets", "micradrone", "textures", "item")
    im = icon()
    save_png(os.path.join(tex_dir, "region_pointer.png"), im)
    preview_dir = os.environ.get("PREVIEW_DIR", here)
    save_png(os.path.join(preview_dir, "preview_region_pointer.png"), upscale(im, 8))
    print("wrote region_pointer.png (32x32) + preview_region_pointer.png (8x)")


if __name__ == "__main__":
    main()
