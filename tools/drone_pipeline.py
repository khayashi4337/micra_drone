# Single source of truth for the drone's model + texture, with a software previewer.
# - PARTS defines the box hierarchy exactly as it will be transcribed into DroneModel.java
# - paint_* functions build the 64x64 texture
# - render_view() draws orthographic previews (front/back/side/bottom/top) using the exact
#   per-face UV slot layout verified from decompiled ModelPart.Cube (NeoForge 21.1.238)
import struct, zlib

TEX_W, TEX_H = 64, 64
tex = [[(0, 0, 0, 0) for _ in range(TEX_W)] for _ in range(TEX_H)]

# ---------------- palette (sampled by eye from the reference renders) ----------------
CYAN      = (94, 205, 197, 255)   # main hull
CYAN_HI   = (154, 232, 224, 255)  # top / highlight
CYAN_LO   = (58, 158, 156, 255)   # shaded hull
SCREEN    = (10, 16, 22, 255)     # face screen black
GLOW      = (120, 244, 255, 255)  # eye / light glow
GLOW_CORE = (222, 255, 255, 255)  # brightest core (chin light, hub)
PANEL     = (34, 44, 54, 255)     # dark back panel / vents
PANEL_DK  = (18, 24, 32, 255)     # darkest slats
MAST      = (52, 58, 66, 255)     # dark grey masts / nozzle
MAST_DK   = (30, 34, 40, 255)     # darker mast segment
BLADE     = (232, 234, 238, 255)  # white blades
BLADE_TIP = (196, 200, 208, 255)  # grey blade tips


def px(x, y, c):
    if 0 <= x < TEX_W and 0 <= y < TEX_H:
        tex[y][x] = c


def rect(x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            px(xx, yy, c)


# ---------------- box UV slot math (verified: ModelPart.Cube, NeoForge 21.1.238) ----------------
# For texOffs(u,v) and box size (dx,dy,dz):
#   WEST  (x-min): (u,           v+dz) size dz x dy     u-axis: z0->right (u2), z1->left
#   NORTH (z-min): (u+dz,        v+dz) size dx x dy     u-axis: x0->left,  x1->right
#   EAST  (x-max): (u+dz+dx,     v+dz) size dz x dy     u-axis: z0->left,  z1->right
#   SOUTH (z-max): (u+2dz+dx,    v+dz) size dx x dy     u-axis: x0->right, x1->left  (mirrored)
#   DOWN  (y-max): (u+dz,        v)    size dx x dz     v-axis: z1->top, z0->bottom
#   UP    (y-min): (u+dz+dx,     v)    size dx x dz     v-axis: z0->top, z1->bottom
def slots(u, v, dx, dy, dz):
    return {
        'west':  (u,                v + dz, dz, dy),
        'north': (u + dz,           v + dz, dx, dy),
        'east':  (u + dz + dx,      v + dz, dz, dy),
        'south': (u + 2 * dz + dx,  v + dz, dx, dy),
        'down':  (u + dz,           v,      dx, dz),
        'up':    (u + dz + dx,      v,      dx, dz),
    }


def fill_box(u, v, dx, dy, dz, north=None, south=None, east=None, west=None, up=None, down=None):
    s = slots(u, v, dx, dy, dz)
    for name, col in (('north', north), ('south', south), ('east', east),
                      ('west', west), ('up', up), ('down', down)):
        if col is not None:
            x, y, w, h = s[name]
            rect(x, y, w, h, col)


# ---------------- texture painting ----------------
def paint_body():
    u, v, dx, dy, dz = 0, 0, 10, 10, 10
    fill_box(u, v, dx, dy, dz, north=CYAN, south=CYAN, east=CYAN, west=CYAN, up=CYAN_HI, down=PANEL)
    s = slots(u, v, dx, dy, dz)

    # FRONT (north): cyan bezel + 8x8 black screen + eyes + smile
    nx, ny = s['north'][0], s['north'][1]
    rect(nx + 1, ny + 1, 8, 8, SCREEN)
    # eyes: 2 wide x 3 tall, at screen cols 1-2 and 5-6, rows 1-3
    rect(nx + 2, ny + 2, 2, 3, GLOW)
    rect(nx + 6, ny + 2, 2, 3, GLOW)
    # smile: corners up at rows 5, bottom bar row 6  ->  "⌣"
    px(nx + 2, ny + 6, GLOW)
    px(nx + 7, ny + 6, GLOW)
    rect(nx + 3, ny + 7, 4, 1, GLOW)

    # BACK (south): bezel + dark panel + top glow bar + 2 vertical glow slits + slats
    sx, sy = s['south'][0], s['south'][1]
    rect(sx + 1, sy + 1, 8, 8, PANEL)
    rect(sx + 3, sy + 2, 4, 1, GLOW)          # horizontal glow bar
    rect(sx + 2, sy + 4, 1, 3, GLOW)          # left vertical slit
    rect(sx + 7, sy + 4, 1, 3, GLOW)          # right vertical slit
    for row in (4, 6):                        # dark grille slats between the slits
        rect(sx + 4, sy + row, 2, 1, PANEL_DK)
        rect(sx + 4, sy + row + 1, 2, 1, MAST)

    # SIDES: subtle vent
    for name in ('east', 'west'):
        ex, ey = s[name][0], s[name][1]
        rect(ex + 3, ey + 3, 4, 3, CYAN_LO)
        rect(ex + 4, ey + 4, 2, 1, PANEL)

    # BOTTOM: dark belly with cyan rim
    bx, by = s['down'][0], s['down'][1]
    rect(bx, by, 10, 10, CYAN_LO)
    rect(bx + 1, by + 1, 8, 8, PANEL)
    rect(bx + 3, by + 3, 4, 4, PANEL_DK)


def paint_hatch():
    fill_box(0, 20, 6, 1, 6, north=CYAN_HI, south=CYAN_HI, east=CYAN_HI, west=CYAN_HI, up=CYAN_HI, down=CYAN_HI)
    s = slots(0, 20, 6, 1, 6)
    ux, uy = s['up'][0], s['up'][1]
    rect(ux + 1, uy + 1, 4, 4, CYAN)          # inner inset line on the lid


def paint_chin():
    fill_box(26, 20, 4, 4, 1, north=CYAN, south=CYAN, east=CYAN, west=CYAN, up=CYAN, down=CYAN_LO)
    s = slots(26, 20, 4, 4, 1)
    nx, ny = s['north'][0], s['north'][1]
    rect(nx + 1, ny + 1, 2, 2, GLOW_CORE)      # bright white-cyan core light


def paint_nozzle():
    fill_box(0, 27, 4, 2, 4, north=MAST, south=MAST, east=MAST, west=MAST, up=MAST, down=MAST_DK)
    s = slots(0, 27, 4, 2, 4)
    dxx, dyy = s['down'][0], s['down'][1]
    rect(dxx + 1, dyy + 1, 2, 2, GLOW)         # glowing thruster port (seen in bottom render)


def paint_pod():
    fill_box(16, 27, 4, 4, 4, north=CYAN, south=CYAN, east=CYAN, west=CYAN, up=CYAN_HI, down=CYAN_LO)
    s = slots(16, 27, 4, 4, 4)
    for name in ('north', 'south', 'east', 'west'):  # glowing square light ringed dark, all around
        fx, fy = s[name][0], s[name][1]
        rect(fx, fy, 4, 4, CYAN)
        rect(fx + 1, fy + 1, 2, 2, PANEL)
        px(fx + 1, fy + 1, GLOW)
        px(fx + 2, fy + 1, GLOW)
        px(fx + 1, fy + 2, GLOW)
        px(fx + 2, fy + 2, GLOW)
        rect(fx + 1, fy + 1, 2, 2, GLOW)


def paint_masts():
    fill_box(33, 27, 2, 2, 2, north=MAST, south=MAST, east=MAST, west=MAST, up=MAST, down=MAST)
    fill_box(42, 27, 1, 3, 1, north=MAST_DK, south=MAST_DK, east=MAST_DK, west=MAST_DK, up=MAST_DK, down=MAST_DK)


def paint_blades():
    # plate A: 10x1x2 (long on X)
    fill_box(0, 36, 10, 1, 2, north=BLADE_TIP, south=BLADE_TIP, east=BLADE_TIP, west=BLADE_TIP, up=BLADE, down=BLADE_TIP)
    sA = slots(0, 36, 10, 1, 2)
    ux, uy, uw, uh = sA['up']
    rect(ux, uy, 2, 2, BLADE_TIP)              # tips grey
    rect(ux + 8, uy, 2, 2, BLADE_TIP)
    rect(ux + 4, uy, 2, 2, GLOW)               # cyan hub centre
    # plate B: 2x1x10 (long on Z)
    fill_box(0, 40, 2, 1, 10, north=BLADE_TIP, south=BLADE_TIP, east=BLADE_TIP, west=BLADE_TIP, up=BLADE, down=BLADE_TIP)
    sB = slots(0, 40, 2, 1, 10)
    ux, uy, uw, uh = sB['up']
    rect(ux, uy, 2, 2, BLADE_TIP)
    rect(ux, uy + 8, 2, 2, BLADE_TIP)
    rect(ux, uy + 4, 2, 2, GLOW)


# ---------------- model spec (mirrors DroneModel.java 1:1) ----------------
# Each part: name, parent, pivot (rel to parent), boxes [(u,v, x0,y0,z0, dx,dy,dz)]
PARTS = [
    ('root',      None,      (0.0, 24.0, 0.0), []),
    ('body',      'root',    (0.0, -4.0, 0.0), [(0, 0, -5.0, -10.0, -5.0, 10, 10, 10)]),
    ('hatch',     'body',    (0.0, -10.0, 0.0), [(0, 20, -3.0, -1.0, -3.0, 6, 1, 6)]),
    # Chin light module hangs off the hull's lower front edge, half below it (matches the logo:
    # the module's top edge sits at the screen's bottom bezel and it extends below the body).
    ('chin',      'body',    (0.0, -1.5, -5.0), [(26, 20, -2.0, 0.0, -1.0, 4, 4, 1)]),
    ('nozzle',    'body',    (0.0, 0.0, 0.0),  [(0, 27, -2.0, 0.0, -2.0, 4, 2, 4)]),
    ('pod_right', 'body',    (-5.0, -6.0, 0.0), [(16, 27, -4.0, -2.0, -2.0, 4, 4, 4)]),
    ('mast_right', 'pod_right', (-2.0, -2.0, 0.0),
        [(33, 27, -1.0, -2.0, -1.0, 2, 2, 2), (42, 27, -0.5, -5.0, -0.5, 1, 3, 1)]),
    ('prop_right', 'mast_right', (0.0, -5.0, 0.0),
        [(0, 36, -5.0, -1.0, -1.0, 10, 1, 2), (0, 40, -1.0, -1.0, -5.0, 2, 1, 10)]),
    ('pod_left',  'body',    (5.0, -6.0, 0.0), [(16, 27, 0.0, -2.0, -2.0, 4, 4, 4)]),
    ('mast_left', 'pod_left', (2.0, -2.0, 0.0),
        [(33, 27, -1.0, -2.0, -1.0, 2, 2, 2), (42, 27, -0.5, -5.0, -0.5, 1, 3, 1)]),
    ('prop_left', 'mast_left', (0.0, -5.0, 0.0),
        [(0, 36, -5.0, -1.0, -1.0, 10, 1, 2), (0, 40, -1.0, -1.0, -5.0, 2, 1, 10)]),
]


def abs_boxes():
    """Resolve every box to absolute model coords (no rotations in rest pose)."""
    piv = {}
    out = []
    for name, parent, pivot, boxes in PARTS:
        base = piv[parent] if parent else (0.0, 0.0, 0.0)
        p = (base[0] + pivot[0], base[1] + pivot[1], base[2] + pivot[2])
        piv[name] = p
        for (u, v, x0, y0, z0, dx, dy, dz) in boxes:
            out.append({
                'name': name, 'u': u, 'v': v,
                'x0': p[0] + x0, 'y0': p[1] + y0, 'z0': p[2] + z0,
                'x1': p[0] + x0 + dx, 'y1': p[1] + y0 + dy, 'z1': p[2] + z0 + dz,
                'dx': dx, 'dy': dy, 'dz': dz,
            })
    return out


# ---------------- orthographic previewer ----------------
SHADE = {'up': 1.0, 'down': 0.55, 'north': 0.85, 'south': 0.85, 'east': 0.68, 'west': 0.68}


def sample(u, v):
    if 0 <= u < TEX_W and 0 <= v < TEX_H:
        return tex[v][u]
    return (255, 0, 255, 255)


def render_view(view, scale=14):
    """view: front|back|left|bottom|top. Returns (pixels, W, H)."""
    boxes = abs_boxes()
    xs = [b[k] for b in boxes for k in ('x0', 'x1')]
    ys = [b[k] for b in boxes for k in ('y0', 'y1')]
    zs = [b[k] for b in boxes for k in ('z0', 'z1')]
    pad = 1.0
    if view in ('front', 'back'):
        h0, h1, v0, v1 = min(xs) - pad, max(xs) + pad, min(ys) - pad, max(ys) + pad
    elif view == 'left':
        h0, h1, v0, v1 = min(zs) - pad, max(zs) + pad, min(ys) - pad, max(ys) + pad
    else:  # bottom / top
        h0, h1, v0, v1 = min(xs) - pad, max(xs) + pad, min(zs) - pad, max(zs) + pad
    W, H = int((h1 - h0) * scale), int((v1 - v0) * scale)
    img = [[(30, 60, 80, 255) for _ in range(W)] for _ in range(H)]
    zbuf = [[-1e9] * W for _ in range(H)]

    def blit(face, s, hlo, hhi, vlo, vhi, depth, umap, vmap):
        su, sv, sw, sh = s
        shade = SHADE[face]
        x_a, x_b = int((hlo - h0) * scale), int((hhi - h0) * scale)
        y_a, y_b = int((vlo - v0) * scale), int((vhi - v0) * scale)
        for py in range(y_a, y_b):
            fv = (py + 0.5 - y_a) / max(1, (y_b - y_a))
            for pxx in range(x_a, x_b):
                if depth <= zbuf[py][pxx]:
                    continue
                fu = (pxx + 0.5 - x_a) / max(1, (x_b - x_a))
                uu = umap(fu)
                vv = vmap(fv)
                tu = su + min(sw - 1, int(uu * sw))
                tv = sv + min(sh - 1, int(vv * sh))
                r, g, b, a = sample(tu, tv)
                if a == 0:
                    continue
                img[py][pxx] = (int(r * shade), int(g * shade), int(b * shade), 255)
                zbuf[py][pxx] = depth

    for b in boxes:
        s = slots(b['u'], b['v'], b['dx'], b['dy'], b['dz'])
        if view == 'front':   # camera at -z looking +z; screen x = model x, depth = -z (near = z small)
            blit('north', s['north'], b['x0'], b['x1'], b['y0'], b['y1'], -b['z0'], lambda f: f, lambda f: f)
            blit('east',  s['east'],  b['x1'] - 0.001, b['x1'], b['y0'], b['y1'], -b['z0'] - 0.5, lambda f: f, lambda f: f)
        elif view == 'back':  # camera at +z looking -z; screen x = -model x (flip), depth = z
            blit('south', s['south'], -b['x1'], -b['x0'], b['y0'], b['y1'], b['z1'],
                 lambda f: f, lambda f: f)  # south slot u-axis is already mirrored (x0->right)
        elif view == 'left':  # camera at +x looking -x; screen x = -z? use z: depth = x1
            blit('east', s['east'], b['z0'], b['z1'], b['y0'], b['y1'], b['x1'], lambda f: f, lambda f: f)
        elif view == 'bottom':  # camera below looking up (+y is down/model): depth = y1
            blit('down', s['down'], b['x0'], b['x1'], b['z0'], b['z1'], b['y1'],
                 lambda f: f, lambda f: 1.0 - f)  # down slot v-axis: z1->top
        elif view == 'top':
            blit('up', s['up'], b['x0'], b['x1'], b['z0'], b['z1'], -b['y0'], lambda f: f, lambda f: f)
    return img, W, H


def save_png(path, img, W, H):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    raw = bytearray()
    for row in img:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes([r, g, b, a])
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        f.write(chunk(b"IEND", b""))


def main():
    paint_body()
    paint_hatch()
    paint_chin()
    paint_nozzle()
    paint_pod()
    paint_masts()
    paint_blades()
    base = r"C:\Users\kh\AppData\Local\Temp\claude\G--prj2-micra-drone\bacd0476-9693-499e-a202-387451cc3bee\scratchpad"
    save_png(base + r"\drone.png", tex, TEX_W, TEX_H)
    for view in ("front", "back", "left", "bottom", "top"):
        img, W, H = render_view(view)
        save_png(base + rf"\preview_{view}.png", img, W, H)
    print("wrote texture + previews")


if __name__ == "__main__":
    main()
