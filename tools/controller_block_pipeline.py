# Single source of truth for the Drone Controller block's two textures (docked / active), with a
# software previewer. Mirrors drone_pipeline.py's approach: hand-painted 16x16 pixel art (Minecraft's
# standard block-texture resolution), palette sampled by eye from the ChatGPT reference renders in
# F:\Download, one PNG per face per state (a minecraft:block/cube-parent model references east/west
# with the SAME side file, so only 5 unique faces are painted per state: front/back/side/top/bottom).
import struct, zlib

W = H = 16
CANVAS = 8  # preview upscale factor


def blank():
    return [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]


def px(img, x, y, c):
    if 0 <= x < W and 0 <= y < H:
        img[y][x] = c


def rect(img, x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            px(img, xx, yy, c)


# ---------------- palette (sampled by eye from the 3 reference renders) ----------------
HULL      = (45, 172, 168, 255)   # main teal body
HULL_HI   = (112, 210, 204, 255)  # lit top edge
HULL_LO   = (30, 128, 126, 255)   # shaded side/bottom rim
DARK      = (14, 20, 26, 255)     # screen/panel black
DARK_LO   = (8, 12, 16, 255)      # deepest recess (open-top interior core)
GLOW      = (110, 240, 255, 255)  # cyan glow accents (eyes, lights, screen text)
GLOW_DIM  = (60, 165, 185, 255)   # dimmer secondary glow
AMBER     = (235, 170, 70, 255)   # code-line accent color (active front screen)
GREEN     = (120, 210, 90, 255)   # code-line accent / planted-crop accent
BROWN     = (120, 85, 55, 255)    # tilled-soil accent (active front minimap)
GOLD      = (215, 180, 60, 255)   # wheat accent (active front minimap)
TRIM      = (18, 22, 26, 255)     # dark base trim / feet
METAL     = (150, 155, 160, 255)  # grey bolt/spring
METAL_DK  = (90, 95, 100, 255)    # bolt shadow
SCROLL    = (205, 178, 132, 255)  # tan scroll paper (docked front slot)


# ================================================================= DOCKED (格納時)
def docked_front():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    # screen bezel + black screen
    rect(im, 2, 2, 12, 8, DARK)
    # eyes
    rect(im, 4, 4, 2, 3, GLOW)
    rect(im, 10, 4, 2, 3, GLOW)
    # smile (curved: corners up, bar across)
    px(im, 4, 8, GLOW)
    px(im, 11, 8, GLOW)
    rect(im, 5, 9, 6, 1, GLOW)
    # bottom row: button, scroll slot, 2x2 button
    rect(im, 2, 11, 3, 3, DARK)
    px(im, 3, 12, GLOW)
    rect(im, 6, 11, 4, 3, TRIM)
    rect(im, 7, 10, 2, 1, SCROLL)
    rect(im, 6, 12, 4, 1, SCROLL)
    rect(im, 11, 11, 3, 3, DARK)
    px(im, 11, 11, GLOW_DIM); px(im, 13, 11, GLOW_DIM)
    px(im, 11, 13, GLOW_DIM); px(im, 13, 13, GLOW_DIM)
    return im


def docked_side():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    rect(im, 3, 3, 10, 10, DARK)
    rect(im, 4, 4, 8, 8, HULL_LO)
    rect(im, 6, 6, 4, 4, DARK)
    px(im, 7, 7, GLOW); px(im, 8, 7, GLOW)
    px(im, 7, 8, GLOW); px(im, 8, 8, GLOW)
    return im


def docked_back():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    # top hatch strip: two bolt/spring shapes flanking a small hatch bump
    rect(im, 1, 1, 3, 2, METAL); px(im, 2, 1, GLOW_DIM)
    rect(im, 12, 1, 3, 2, METAL); px(im, 13, 1, GLOW_DIM)
    rect(im, 6, 1, 4, 1, HULL_HI)
    px(im, 7, 2, GLOW)
    # main bracket panel
    rect(im, 2, 4, 12, 9, DARK)
    px(im, 3, 5, GLOW); px(im, 12, 5, GLOW)
    px(im, 3, 11, GLOW); px(im, 12, 11, GLOW)
    rect(im, 6, 5, 3, 1, METAL_DK)
    rect(im, 6, 11, 3, 1, METAL_DK)
    rect(im, 7, 7, 2, 3, GLOW)
    return im


def docked_bottom():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL_LO)
    rect(im, 1, 1, 14, 14, DARK)
    for cx, cy in ((2, 2), (12, 2), (2, 12), (12, 12)):
        rect(im, cx, cy, 2, 2, METAL_DK)
        px(im, cx, cy, METAL)
    px(im, 8, 5, GLOW_DIM); px(im, 5, 8, GLOW_DIM)
    px(im, 10, 8, GLOW_DIM); px(im, 8, 10, GLOW_DIM)
    rect(im, 7, 7, 2, 2, GLOW)
    return im


def docked_top():
    # No reference view exists for this face (extrapolated from the back panel's top-edge bolts).
    im = blank()
    rect(im, 0, 0, 16, 16, HULL_HI)
    rect(im, 1, 1, 14, 14, HULL)
    for cx, cy in ((2, 2), (12, 2), (2, 12), (12, 12)):
        px(im, cx, cy, METAL)
    rect(im, 6, 7, 4, 2, DARK)
    rect(im, 7, 7, 2, 2, GLOW_DIM)
    return im


# ================================================================= ACTIVE (稼働時)
def active_front():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    # left: terminal (code lines)
    rect(im, 1, 2, 7, 12, DARK)
    rect(im, 2, 3, 4, 1, GLOW)
    rect(im, 2, 5, 3, 1, AMBER)
    rect(im, 2, 7, 5, 1, GREEN)
    rect(im, 2, 9, 2, 1, GLOW)
    px(im, 2, 11, GLOW)
    # right: mini farm map
    rect(im, 8, 2, 7, 12, DARK)
    rect(im, 9, 3, 2, 2, BROWN); rect(im, 11, 3, 2, 2, GREEN); rect(im, 13, 3, 1, 2, GOLD)
    rect(im, 9, 6, 2, 2, GOLD); rect(im, 13, 6, 1, 2, BROWN)
    rect(im, 9, 9, 2, 2, BROWN); rect(im, 11, 9, 2, 2, GREEN); rect(im, 13, 9, 1, 2, GOLD)
    rect(im, 11, 6, 2, 2, GLOW)  # tiny drone marker
    return im


def active_side():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    # vent slats
    rect(im, 4, 2, 8, 3, DARK)
    for x in range(5, 12, 2):
        rect(im, x, 3, 1, 1, GLOW_DIM)
    # drawer
    rect(im, 3, 7, 10, 6, DARK)
    for y in (8, 10, 12):
        rect(im, 4, y, 8, 1, METAL_DK)
    return im


def active_back():
    # Close variant of docked_back (reference shows the same bracket-panel family for both states);
    # kept visually distinct mainly via the top-edge accent so the block still reads as "one object".
    im = blank()
    rect(im, 0, 0, 16, 16, HULL)
    rect(im, 0, 14, 16, 2, TRIM)
    rect(im, 2, 2, 12, 11, DARK)
    px(im, 3, 3, GLOW); px(im, 12, 3, GLOW)
    px(im, 3, 11, GLOW); px(im, 12, 11, GLOW)
    rect(im, 6, 3, 3, 1, METAL_DK)
    rect(im, 6, 11, 3, 1, METAL_DK)
    rect(im, 7, 6, 2, 3, GLOW)
    return im


def active_bottom():
    im = blank()
    rect(im, 0, 0, 16, 16, HULL_LO)
    rect(im, 1, 1, 14, 14, DARK)
    for cx, cy in ((2, 2), (12, 2), (2, 12), (12, 12)):
        rect(im, cx, cy, 2, 2, METAL)
        px(im, cx + 1, cy + 1, METAL_DK)
    rect(im, 7, 1, 2, 14, GLOW_DIM)
    rect(im, 1, 7, 14, 2, GLOW_DIM)
    return im


def active_top():
    # Open hollow interior: rim lit, fading to near-black core.
    im = blank()
    rect(im, 0, 0, 16, 16, HULL_HI)
    rect(im, 1, 1, 14, 14, HULL_LO)
    rect(im, 3, 3, 10, 10, DARK)
    rect(im, 5, 5, 6, 6, DARK_LO)
    return im


FACES = {
    "drone_controller_docked_front": docked_front,
    "drone_controller_docked_side": docked_side,
    "drone_controller_docked_back": docked_back,
    "drone_controller_docked_bottom": docked_bottom,
    "drone_controller_docked_top": docked_top,
    "drone_controller_active_front": active_front,
    "drone_controller_active_side": active_side,
    "drone_controller_active_back": active_back,
    "drone_controller_active_bottom": active_bottom,
    "drone_controller_active_top": active_top,
}


def save_png(path, img, w, h):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = bytearray()
    for row in img:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes([r, g, b, a])
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def upscale(img, factor):
    out = [[(0, 0, 0, 0) for _ in range(W * factor)] for _ in range(H * factor)]
    for y in range(H):
        for x in range(W):
            c = img[y][x]
            for dy in range(factor):
                for dx in range(factor):
                    out[y * factor + dy][x * factor + dx] = c
    return out


def contact_sheet(names, cols, factor):
    """Arrange several upscaled faces left-to-right, top-to-bottom, 2px gaps, for one glance-review PNG."""
    cell = W * factor
    gap = 2
    rows = (len(names) + cols - 1) // cols
    sheet_w = cols * cell + (cols + 1) * gap
    sheet_h = rows * cell + (rows + 1) * gap
    sheet = [[(40, 40, 40, 255) for _ in range(sheet_w)] for _ in range(sheet_h)]
    for i, name in enumerate(names):
        img = upscale(FACES[name](), factor)
        cx = gap + (i % cols) * (cell + gap)
        cy = gap + (i // cols) * (cell + gap)
        for y in range(cell):
            for x in range(cell):
                sheet[cy + y][cx + x] = img[y][x]
    return sheet, sheet_w, sheet_h


def main():
    tex_dir = r"G:\prj2\micra_drone\src\main\resources\assets\micradrone\textures\block"
    for name, fn in FACES.items():
        save_png(tex_dir + "\\" + name + ".png", fn(), W, H)

    base = r"C:\Users\kh\AppData\Local\Temp\claude\G--prj2-micra-drone\bacd0476-9693-499e-a202-387451cc3bee\scratchpad"
    docked_order = ["drone_controller_docked_front", "drone_controller_docked_side",
                     "drone_controller_docked_back", "drone_controller_docked_top",
                     "drone_controller_docked_bottom"]
    active_order = ["drone_controller_active_front", "drone_controller_active_side",
                     "drone_controller_active_back", "drone_controller_active_top",
                     "drone_controller_active_bottom"]
    sheet, w, h = contact_sheet(docked_order, 5, 10)
    save_png(base + r"\preview_docked.png", sheet, w, h)
    sheet, w, h = contact_sheet(active_order, 5, 10)
    save_png(base + r"\preview_active.png", sheet, w, h)
    print("wrote 10 face textures + 2 preview sheets")


if __name__ == "__main__":
    main()
