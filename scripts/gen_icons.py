#!/usr/bin/env python3
"""
Generate BreakInv icon assets using only Python stdlib.

Source design:  packaging/branding/breakinv-icon.svg
Outputs (paths relative to repo root):
  src/main/resources/app/icon.png   — 256x256 PNG loaded by App.java at startup
  packaging/windows/BreakInv.ico    — Multi-size ICO (256, 48, 32, 16 px) for jpackage
  packaging/linux/breakinv.png      — 256x256 PNG for the Linux .desktop icon

Run from any directory:
  python scripts/gen_icons.py
"""
import math
import os
import struct
import zlib

# ── Brand colours ─────────────────────────────────────────────────────────────
BG    = (11,  18,  32)    # #0B1220  dark background
GREEN = (34, 197,  94)    # #22C55E  brand green
GOLD  = (245, 158,  11)   # #F59E0B  accent gold

# ── Pixel helpers ─────────────────────────────────────────────────────────────
def _set(grid, x, y, color):
    n = len(grid)
    if 0 <= x < n and 0 <= y < n:
        grid[y][x] = color


def fill_rect(grid, x0, y0, w, h, color):
    for dy in range(h):
        for dx in range(w):
            _set(grid, x0 + dx, y0 + dy, color)


def fill_disk(grid, cx, cy, r, color):
    r2 = r * r
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            if dx * dx + dy * dy <= r2:
                _set(grid, cx + dx, cy + dy, color)


def draw_thick_line(grid, x0, y0, x1, y1, thickness, color):
    r  = max(0, thickness // 2)
    dx = x1 - x0
    dy = y1 - y0
    steps = max(abs(dx), abs(dy)) + 1
    for i in range(steps + 1):
        t  = i / steps
        px = int(round(x0 + t * dx))
        py = int(round(y0 + t * dy))
        fill_disk(grid, px, py, r, color)


def fill_triangle(grid, p0, p1, p2, color):
    pts = sorted([p0, p1, p2], key=lambda p: p[1])
    n   = len(grid)

    def interp_x(pa, pb, y):
        if pa[1] == pb[1]:
            return float(pa[0])
        return pa[0] + (pb[0] - pa[0]) * (y - pa[1]) / (pb[1] - pa[1])

    for y in range(max(0, pts[0][1]), min(n, pts[2][1] + 1)):
        if y <= pts[1][1]:
            xa = interp_x(pts[0], pts[1], y)
            xb = interp_x(pts[0], pts[2], y)
        else:
            xa = interp_x(pts[1], pts[2], y)
            xb = interp_x(pts[0], pts[2], y)
        for x in range(int(min(xa, xb)), int(max(xa, xb)) + 1):
            _set(grid, x, y, color)


# ── Icon pixel generator ──────────────────────────────────────────────────────
def gen_pixels(size: int) -> list:
    """
    BreakInv mark: bold geometric B monogram + rising gold arrow.
    Design source: packaging/branding/breakinv-icon.svg
    """
    grid = [[BG] * size for _ in range(size)]
    S = size / 256.0

    def s(v):
        # round-half-up scaling; always at least 1 px
        return max(1, int(v * S + 0.5))

    # ── B structural values (all computed relative to each other so pieces
    #    connect cleanly at every scale) ────────────────────────────────────
    sx    = s(52)           # left edge of stem
    sw    = s(28)           # stem width
    bx    = sx + sw         # x where bars/bumps start (right of stem)
    ey    = s(44)           # top of B
    eh    = s(168)          # total B height
    bar_h = s(24)           # horizontal bar height

    top_w = s(88)           # top bar width  (right edge aligns with upper bump)
    mid_w = s(80)           # mid bar width  (slightly shorter — typical B form)
    bot_w = s(100)          # bottom bar width (wider — typical B form)
    ur_w  = s(24)           # upper-right bump width
    lr_w  = s(24)           # lower-right bump width

    # Y positions — computed so every piece connects to its neighbours
    top_y = ey
    ub_y  = top_y + bar_h                          # upper bump start
    mid_y = ey + (eh - bar_h) // 2                 # mid bar (centred in B)
    ub_h  = max(1, mid_y - ub_y)                   # upper bump height
    lb_y  = mid_y + bar_h                          # lower bump start
    bot_y = ey + eh - bar_h                        # bottom bar start
    lb_h  = max(1, bot_y - lb_y)                   # lower bump height

    # X positions for bumps (flush with the right edge of their adjacent bar)
    ur_x  = bx + top_w - ur_w
    lr_x  = bx + bot_w - lr_w

    # ── B monogram (green) ─────────────────────────────────────────────────
    fill_rect(grid, sx,   ey,    sw,    eh,    GREEN)  # left stem
    fill_rect(grid, bx,   top_y, top_w, bar_h, GREEN)  # top bar
    fill_rect(grid, ur_x, ub_y,  ur_w,  ub_h,  GREEN)  # upper-right bump
    fill_rect(grid, bx,   mid_y, mid_w, bar_h, GREEN)  # mid bar
    fill_rect(grid, lr_x, lb_y,  lr_w,  lb_h,  GREEN)  # lower-right bump
    fill_rect(grid, bx,   bot_y, bot_w, bar_h, GREEN)  # bottom bar

    # ── Rising gold arrow (drawn last — overlays the B) ────────────────────
    ax0, ay0 = s(38),  s(200)
    ax1, ay1 = s(206), s(64)
    draw_thick_line(grid, ax0, ay0, ax1, ay1, max(1, s(12)), GOLD)

    # Arrowhead triangle at tip (ax1, ay1)
    adx = ax1 - ax0
    ady = ay1 - ay0
    length = math.hypot(adx, ady)
    if length > 0:
        nx, ny = adx / length, ady / length      # unit along arrow direction
        px, py = -ny, nx                          # unit perpendicular
        hl     = max(2, s(28))                    # head length
        hw     = max(1, s(14))                    # head half-width
        bcx    = ax1 - int(nx * hl)
        bcy    = ay1 - int(ny * hl)
        b1 = (bcx + int(px * hw), bcy + int(py * hw))
        b2 = (bcx - int(px * hw), bcy - int(py * hw))
        fill_triangle(grid, (ax1, ay1), b1, b2, GOLD)

    return grid


# ── Minimal PNG writer (no external deps) ────────────────────────────────────
def _chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def make_png(pixels: list) -> bytes:
    h, w = len(pixels), len(pixels[0])
    raw  = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )


# ── ICO writer (PNG-in-ICO, Vista+ format) ───────────────────────────────────
def make_ico(sizes: list) -> bytes:
    """
    Build a Windows ICO file containing one PNG image per requested size.
    PNG-in-ICO is supported on Windows Vista and later, and by jpackage.
    """
    entries = [(sz, make_png(gen_pixels(sz))) for sz in sizes]
    count   = len(entries)

    # ICONDIR header: reserved=0, type=1 (icon), count
    header  = struct.pack("<HHH", 0, 1, count)
    dir_off = 6 + count * 16   # offset to first image data

    dirs  = b""
    blobs = b""
    for sz, png in entries:
        # bWidth/bHeight: 0 means 256; for smaller sizes use the actual value
        w = 0 if sz >= 256 else sz
        h = 0 if sz >= 256 else sz
        # ICONDIRENTRY: width, height, colorCount, reserved, planes, bitCount,
        #               bytesInRes, imageOffset
        dirs  += struct.pack("<BBBBHHII", w, h, 0, 0, 1, 32, len(png), dir_off)
        blobs += png
        dir_off += len(png)

    return header + dirs + blobs


# ── Helpers ───────────────────────────────────────────────────────────────────
def write(path: str, data: bytes) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(data)
    print(f"  wrote  {path}  ({len(data):,} bytes)")


# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    # Resolve repo root regardless of where the script is invoked from
    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    png256 = make_png(gen_pixels(256))
    ico    = make_ico([256, 48, 32, 16])

    write(os.path.join(repo, "src/main/resources/app/icon.png"), png256)
    write(os.path.join(repo, "packaging/windows/BreakInv.ico"),  ico)
    write(os.path.join(repo, "packaging/linux/breakinv.png"),    png256)

    print("Done.")
