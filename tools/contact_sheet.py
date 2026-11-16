#!/usr/bin/env python3
"""Tiles our own PNG screenshots (RGBA, filter 0) into one downscaled sheet."""
import struct, sys, zlib
def read(p):
    d = open(p, 'rb').read(); i = 8; idat = b''
    while i < len(d):
        n, t = struct.unpack('>I4s', d[i:i+8]); c = d[i+8:i+8+n]
        if t == b'IHDR': w, h = struct.unpack('>II', c[:8])
        if t == b'IDAT': idat += c
        i += 12 + n
    raw = zlib.decompress(idat); s = w * 4 + 1
    return w, h, [raw[y*s+1:(y+1)*s] for y in range(h)]
def write(p, w, h, rows):
    raw = b''.join(b'\0' + r for r in rows)
    def ch(t, c): return struct.pack('>I', len(c)) + t + c + struct.pack('>I', zlib.crc32(t + c) & 0xffffffff)
    open(p, 'wb').write(b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw, 6)) + ch(b'IEND', b''))
out, scale, cols, files = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4:]
imgs = []
for f in files:
    w, h, rows = read(f)
    small = []
    for y in range(0, h, scale):
        r = rows[y]; small.append(b''.join(r[x*4:x*4+4] for x in range(0, w, scale)))
    imgs.append((len(small[0]) // 4, len(small), small))
tw, th = imgs[0][0], imgs[0][1]; rws = (len(imgs) + cols - 1) // cols
sheet = []
for ry in range(rws):
    for y in range(th):
        line = b''
        for cx in range(cols):
            k = ry * cols + cx
            line += imgs[k][2][y] if k < len(imgs) and y < imgs[k][1] else b'\0' * tw * 4
        sheet.append(line)
write(out, tw * cols, th * rws, sheet)
