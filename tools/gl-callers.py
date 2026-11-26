#!/usr/bin/env python3
# Lists every game function that reads a GL function pointer of the glad table (ADRP to its pages, then LDR/ADD
# with an immediate offset from that register), with the GL names it uses.
# Usage: tools/gl-callers.py <factorio ELF> <glad page hex,...> <offset->name map file>
#   map file: lines "0xe30 glBindFramebuffer" (offsets inside the first page; +0x1000 for the next page).
#   2.1.19: tools/gl-callers.py game/bin/arm64/factorio 0x40a0000,0x40a1000 analysis/glad-map.txt
# Needs llvm-nm and c++filt. Pure Python scan of the executable segment (~1 min on the phone).
import bisect, struct, subprocess, sys
from collections import defaultdict

path = sys.argv[1]
pages = [int(p, 16) for p in sys.argv[2].split(',')]
names = {}
for line in open(sys.argv[3]):
    p = line.split()
    if len(p) >= 2:
        names[int(p[0], 16)] = p[1]
data = open(path, 'rb').read()
phoff, = struct.unpack_from('<Q', data, 0x20)
phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
segs = []
for i in range(phnum):
    p_type, p_flags, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phentsize)
    if p_type == 1 and p_flags & 1:
        segs.append((p_offset, p_vaddr, p_filesz))
nm = subprocess.run(['llvm-nm', '--defined-only', path], capture_output=True, text=True).stdout.split('\n')
syms = sorted((int(l.split()[0], 16), l.split(' ', 2)[2]) for l in nm if len(l.split(' ', 2)) == 3 and l.split()[1] in 'tTwW')
dem = subprocess.run(['c++filt'], input='\n'.join(s for _, s in syms), capture_output=True, text=True).stdout.split('\n')
addrs = [a for a, _ in syms]
base = min(pages)
uses = defaultdict(set)
for off, vaddr, size in segs:
    n = size // 4
    words = struct.unpack_from('<%dI' % n, data, off)
    for i, w in enumerate(words):
        if w & 0x9f000000 != 0x90000000:  # ADRP
            continue
        pc = vaddr + i * 4
        imm = ((w >> 29) & 3) | (((w >> 5) & 0x7ffff) << 2)
        if imm & (1 << 20):
            imm -= 1 << 21
        page = (pc & ~0xfff) + (imm << 12)
        if page not in pages:
            continue
        rd = w & 31
        for j in range(i + 1, min(i + 24, n)):
            x = words[j]
            rn = (x >> 5) & 31
            if x & 0xffc00000 == 0xf9400000 and rn == rd:  # LDR Xt, [Xn, #imm]
                o = page - base + ((x >> 10) & 0xfff) * 8
            elif x & 0xff800000 == 0x91000000 and rn == rd:  # ADD Xd, Xn, #imm
                o = page - base + ((x >> 10) & 0xfff)
            else:
                if x & 31 == rd and x & 0x9f000000 == 0x90000000:
                    break  # register reloaded
                continue
            k = bisect.bisect_right(addrs, pc) - 1
            uses[dem[k] if k >= 0 else hex(pc)].add(names.get(o, 'glad+%#x' % o))
            break
for f in sorted(uses):
    print(f'{f}\n    ' + ' '.join(sorted(uses[f])))
