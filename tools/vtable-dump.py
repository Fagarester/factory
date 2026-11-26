#!/usr/bin/env python3
# Dump C++ vtables of the game binary: slot number, byte offset in the vtable (as used by "ldr x8,[x8,#off]"),
# target address and demangled function name.
# Usage: tools/vtable-dump.py <factorio ELF> <class regex> [...]
#   e.g. tools/vtable-dump.py game/bin/arm64/factorio '^GraphicsInterface$' 'OpenGL$'
# Needs llvm-nm and c++filt in PATH. Vtable entries in the PIE are R_AARCH64_RELATIVE relocations (addend = target).
import bisect, re, struct, subprocess, sys


def sections(data):
    shoff, = struct.unpack_from('<Q', data, 0x28)
    shentsize, shnum, shstrndx = struct.unpack_from('<HHH', data, 0x3a)
    hdrs = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + i * shentsize) for i in range(shnum)]
    strtab = hdrs[shstrndx]
    name = lambda off: data[strtab[4] + off:data.index(b'\0', strtab[4] + off)].decode()
    return {name(h[0]): h for h in hdrs}


def relocs(data, secs):
    out = {}
    for n in ('.rela.dyn',):
        h = secs[n]
        for off in range(h[4], h[4] + h[5], 24):
            r_off, r_info, r_add = struct.unpack_from('<QQq', data, off)
            if r_info & 0xffffffff == 1027:  # R_AARCH64_RELATIVE
                out[r_off] = r_add
    return out


def main():
    path, pats = sys.argv[1], [re.compile(p) for p in sys.argv[2:]]
    data = open(path, 'rb').read()
    rel = relocs(data, sections(data))
    nm = subprocess.run(['llvm-nm', '--defined-only', path], capture_output=True, text=True).stdout.split('\n')
    syms = []
    for line in nm:
        p = line.split(' ', 2)
        if len(p) == 3:
            syms.append((int(p[0], 16), p[2]))
    syms.sort()
    dem = subprocess.run(['c++filt'], input='\n'.join(s for _, s in syms), capture_output=True,
                         text=True).stdout.split('\n')
    addrs = [a for a, _ in syms]
    funcs = {}
    for (a, s), d in zip(syms, dem):
        funcs.setdefault(a, d)
    for i, (a, s) in enumerate(syms):
        d = dem[i]
        if not d.startswith('vtable for '):
            continue
        cls = d[len('vtable for '):]
        if not any(p.search(cls) for p in pats):
            continue
        end = addrs[bisect.bisect_right(addrs, a)] if bisect.bisect_right(addrs, a) < len(addrs) else a + 8 * 64
        print(f'== {cls}  vtable {a:#x}  (object vptr = vtable+16)')
        for slot, off in enumerate(range(a + 16, end, 8)):
            t = rel.get(off)
            if t is None:
                v, = struct.unpack_from('<Q', data, off)
                if v == 0:
                    print(f'  [{slot:2}] +{slot*8:<4} (null / pure virtual or end)')
                    continue
                break
            print(f'  [{slot:2}] +{slot*8:<4} {t:#010x} {funcs.get(t, "?")}')


main()
