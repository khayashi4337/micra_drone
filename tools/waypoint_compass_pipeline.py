# Single source of truth for the Waypoint Compass item icon: 32x32 pixel art (same resolution as
# the Region Pointer icon) hand-painted from the ChatGPT reference (F:\Download\ChatGPT Image
# 2026年9月7日 14_34_11.png) - a pocket-watch-style brass compass with a hanging loop at top, a dark
# navy face with light-gray cardinal tick marks, and a glowing cyan crystal needle (this mod's own
# accent color, not vanilla's red/white) pivoting through a bright cyan gem.
#
# Rotates the same way vanilla's own compass does: 32 discrete frames (11.25 degrees apart), picked
# client-side by ItemProperties.register()'s "angle" predicate (see WaypointCompassClient.java) -
# reusing vanilla's own CompassItemPropertyFunction for the wobble/rotation math, just pointed at
# this mod's own WaypointData instead of a lodestone. Frame 0 = needle pointing straight up (toward
# the icon's own top edge); angle increases clockwise, one frame per (2*pi/32) step - matching how
# a real compass needle sweeps as the bearing to its target changes.
#
# Layout is driven by the named constants below; only the per-pixel shading offsets inside the
# paint functions are literal (they ARE the drawing, as in controller_block_pipeline.py).
import json
import math
import os

from pixel_png import blank, px, rect, save_png, upscale

W = H = 32
FRAME_COUNT = 32

GOLD     = (196, 154, 62, 255)
GOLD_HI  = (232, 194, 108, 255)
GOLD_LO  = (140, 104, 34, 255)
BRONZE   = (120, 84, 34, 255)
BRONZE_HI = (160, 118, 56, 255)
DARK     = (30, 34, 42, 255)
DARK_LO  = (20, 23, 29, 255)
TICK     = (176, 180, 186, 255)
GLOW      = (110, 240, 255, 255)   # this mod's own cyan accent (matches RegionSelectionRenderer)
GLOW_HI   = (210, 250, 255, 255)
GLOW_MID  = (70, 200, 220, 255)
GLOW_LO   = (35, 130, 150, 255)

CASE_CENTER = (16, 19)
RING_OUTER = 12.5
RING_INNER = 10.0
RING_SHADE_THRESHOLD = 5    # (dx+dy) beyond +-this is the lit/shaded part of the bevelled rim
TICK_OUTER_R = 9.6
TICK_INNER_R = 7.6
TICK_HALF_WIDTH = 1         # in the perpendicular direction, how wide each tick mark is
NEEDLE_HALF_LEN = 7
NEEDLE_WIDTH = ((0, 0), (1, 0), (0, 1))   # extra pixels (relative) giving the needle its ~2px body


def paint_loop(img):
    # A small bronze bridge above the case with a punched-out hole, like a pocket-watch bail.
    cx, cy = CASE_CENTER
    top = cy - 13
    rect(img, cx - 4, top, 9, 3, BRONZE)
    rect(img, cx - 4, top, 9, 1, BRONZE_HI)
    rect(img, cx - 3, top + 3, 7, 4, GOLD)
    rect(img, cx - 3, top + 3, 7, 1, GOLD_HI)
    rect(img, cx - 1, top + 4, 3, 2, (0, 0, 0, 0))  # the hole


def paint_case(img):
    cx, cy = CASE_CENTER
    for y in range(H):
        for x in range(W):
            d = math.hypot(x - cx, y - cy)
            if d < RING_INNER:
                px(img, x, y, DARK if d < RING_INNER - 1 else DARK_LO)
            elif d < RING_OUTER:
                shade = (x - cx) + (y - cy)
                px(img, x, y, GOLD_HI if shade < -RING_SHADE_THRESHOLD
                   else GOLD_LO if shade > RING_SHADE_THRESHOLD else GOLD)


def paint_ticks(img):
    cx, cy = CASE_CENTER
    for angle_deg in (0, 90, 180, 270):
        rad = math.radians(angle_deg)
        dirx, diry = math.sin(rad), -math.cos(rad)
        perpx, perpy = -diry, dirx
        for r in range(int(TICK_INNER_R), int(TICK_OUTER_R) + 1):
            for w in range(-TICK_HALF_WIDTH, TICK_HALF_WIDTH + 1):
                x = round(cx + dirx * r + perpx * w)
                y = round(cy + diry * r + perpy * w)
                px(img, x, y, TICK)


def needle_color(t):
    # Dark teal at the tail, brightening through the centre pivot, to a near-white highlight at
    # the tip - the gem-line look in the reference image.
    if t <= -3:
        return GLOW_LO
    if t <= -1:
        return GLOW_MID
    if t == 0:
        return GLOW_HI
    if t <= 2:
        return GLOW
    if t <= 4:
        return GLOW_MID
    return GLOW_LO


def paint_needle(img, angle_rad):
    cx, cy = CASE_CENTER
    dirx, diry = math.sin(angle_rad), -math.cos(angle_rad)   # 0 rad = straight up
    perpx, perpy = -diry, dirx
    for t in range(-NEEDLE_HALF_LEN, NEEDLE_HALF_LEN + 1):
        base_x, base_y = cx + dirx * t, cy + diry * t
        color = needle_color(t)
        for dx, dy in NEEDLE_WIDTH:
            px(img, round(base_x + perpx * dx), round(base_y + perpy * dy), color)
    # A slightly larger, brighter pivot gem right at the centre - small enough not to need rotating.
    px(img, cx, cy, GLOW_HI)
    px(img, cx - 1, cy, GLOW)
    px(img, cx, cy + 1, GLOW)
    px(img, cx + 1, cy - 1, GLOW_HI)


def frame(angle_rad):
    im = blank(W, H)
    paint_loop(im)
    paint_case(im)
    paint_ticks(im)
    paint_needle(im, angle_rad)
    return im


def frame_name(i):
    return f"waypoint_compass_{i:02d}"


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    tex_dir = os.path.join(here, "..", "src", "main", "resources", "assets", "micradrone", "textures", "item")
    model_dir = os.path.join(here, "..", "src", "main", "resources", "assets", "micradrone", "models", "item")
    preview_dir = os.environ.get("PREVIEW_DIR", here)
    os.makedirs(tex_dir, exist_ok=True)
    os.makedirs(model_dir, exist_ok=True)

    frames = [frame(2 * math.pi * i / FRAME_COUNT) for i in range(FRAME_COUNT)]
    for i, im in enumerate(frames):
        name = frame_name(i)
        save_png(os.path.join(tex_dir, f"{name}.png"), im)
        with open(os.path.join(model_dir, f"{name}.json"), "w") as f:
            json.dump({"parent": "minecraft:item/generated",
                       "textures": {"layer0": f"micradrone:item/{name}"}}, f, indent=2)
            f.write("\n")

    # Main model: frame 0's own texture as the base, plus threshold overrides for the rest - same
    # "midpoint of each frame's angle range" scheme vanilla's compass.json uses, just in straight
    # ascending order (frame i at threshold (2i+1)/64) since these are new frames, not vanilla's.
    overrides = [{"predicate": {"angle": 0.0}, "model": f"micradrone:item/{frame_name(0)}"}]
    for i in range(1, FRAME_COUNT):
        overrides.append({"predicate": {"angle": (2 * i - 1) / (2 * FRAME_COUNT)},
                           "model": f"micradrone:item/{frame_name(i)}"})
    main_model = {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"micradrone:item/{frame_name(0)}"},
        "overrides": overrides,
    }
    with open(os.path.join(model_dir, "waypoint_compass.json"), "w") as f:
        json.dump(main_model, f, indent=2)
        f.write("\n")

    # Preview: an 8x contact sheet isn't practical for 32 frames, so just upscale frame 0 (the
    # unbound/default look) plus a couple of representative rotations for a quick glance.
    save_png(os.path.join(preview_dir, "preview_waypoint_compass_00.png"), upscale(frames[0], 8))
    save_png(os.path.join(preview_dir, "preview_waypoint_compass_08.png"), upscale(frames[8], 8))
    save_png(os.path.join(preview_dir, "preview_waypoint_compass_16.png"), upscale(frames[16], 8))
    save_png(os.path.join(preview_dir, "preview_waypoint_compass_24.png"), upscale(frames[24], 8))
    print(f"wrote {FRAME_COUNT} textures + models (waypoint_compass_00..{FRAME_COUNT - 1:02d}) "
          "+ waypoint_compass.json + 4 preview frames (0/8/16/24)")


if __name__ == "__main__":
    main()
