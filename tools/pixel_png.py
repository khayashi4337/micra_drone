# Shared helpers for the hand-painted pixel-art pipelines in this folder: an RGBA canvas as a
# list-of-rows of (r,g,b,a) tuples, a dependency-free PNG writer, and a contact-sheet previewer.
# controller_block_pipeline.py / drone_pipeline.py predate this file and still carry their own
# copies; new pipelines import from here instead of adding a third copy.
import struct
import zlib


def blank(w, h):
    return [[(0, 0, 0, 0) for _ in range(w)] for _ in range(h)]


def px(img, x, y, c):
    if 0 <= y < len(img) and 0 <= x < len(img[0]):
        img[y][x] = c


def rect(img, x, y, w, h, c):
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            px(img, xx, yy, c)


def save_png(path, img):
    h = len(img)
    w = len(img[0])

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
    h = len(img)
    w = len(img[0])
    out = blank(w * factor, h * factor)
    for y in range(h):
        for x in range(w):
            c = img[y][x]
            for dy in range(factor):
                for dx in range(factor):
                    out[y * factor + dy][x * factor + dx] = c
    return out


def contact_sheet(images, cols, factor, background=(40, 40, 40, 255)):
    """Upscaled images left-to-right, top-to-bottom with 2px gaps - one glance-review PNG."""
    cell_w = len(images[0][0]) * factor
    cell_h = len(images[0]) * factor
    gap = 2
    rows = (len(images) + cols - 1) // cols
    sheet = [[background for _ in range(cols * cell_w + (cols + 1) * gap)]
             for _ in range(rows * cell_h + (rows + 1) * gap)]
    for i, img in enumerate(images):
        big = upscale(img, factor)
        cx = gap + (i % cols) * (cell_w + gap)
        cy = gap + (i // cols) * (cell_h + gap)
        for y in range(cell_h):
            for x in range(cell_w):
                if big[y][x][3] != 0:
                    sheet[cy + y][cx + x] = big[y][x]
    return sheet
