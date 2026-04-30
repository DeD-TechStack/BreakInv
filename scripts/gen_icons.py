#!/usr/bin/env python3
"""
Generate BreakInv icon assets using only Python stdlib.

Outputs (paths relative to the repo root):
  src/main/resources/app/icon.png   — 256x256 PNG loaded by App.java at startup
  packaging/windows/BreakInv.ico    — Multi-size ICO (256, 48, 32, 16 px) for jpackage
  packaging/linux/breakinv.png      — 256x256 PNG for the Linux .desktop icon

Run from any directory:
  python scripts/gen_icons.py
"""
import os
import struct
import zlib

# ── Brand colours ────────────────────────────────────────────────────────────
BG    = (11,  18,  32)   # #0B1220  dark background
GREEN = (34, 197,  94)   # #22C55E  brand green accent

# ── Minimal PNG writer (no external deps) ────────────────────────────────────
def _chunk(tag: bytes, data: bytes) -> bytes:
    crc = zlib.crc32(tag + data) & 0xFFFFFFFF
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)


def make_png(pixels: list) -> bytes:
    h, w = len(pixels), len(pixels[0])
    # Filter byte 0x00 (None) prepended to each row, then RGB pixels flat
    raw = b"".join(b"\x00" + bytes(c for px in row for c in px) for row in pixels)
    return (
        b"\x89PNG\r\n\x1a\n"
        + _chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
        + _chunk(b"IDAT", zlib.compress(raw, 9))
        + _chunk(b"IEND", b"")
    )


# ── Icon pixel generator ──────────────────────────────────────────────────────
def gen_pixels(size: int) -> list:
    """
    Three ascending bar-chart bars on a dark background.
    Scales from 16x16 to 256x256 using relative proportions.
    """
    grid = [[BG] * size for _ in range(size)]

    margin  = max(1, size // 8)
    content = size - 2 * margin
    gap     = max(1, content // 16)
    n_bars  = 3
    bar_w   = max(1, (content - (n_bars - 1) * gap) // n_bars)

    # Heights as fractions of the content area — short, medium, tall
    heights = [0.44, 0.64, 0.84]

    for i, frac in enumerate(heights):
        x0    = margin + i * (bar_w + gap)
        x1    = x0 + bar_w
        bar_h = max(1, round(content * frac))
        y0    = size - margin - bar_h
        y1    = size - margin
        for y in range(y0, y1):
            for x in range(x0, x1):
                if 0 <= x < size and 0 <= y < size:
                    grid[y][x] = GREEN

    return grid


# ── ICO writer (PNG-in-ICO, Vista+ format) ───────────────────────────────────
def make_ico(sizes: list) -> bytes:
    """
    Build a Windows ICO file containing one PNG image per requested size.
    PNG-in-ICO is supported on Windows Vista and later, and by jpackage.
    """
    entries = [(s, make_png(gen_pixels(s))) for s in sizes]
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
