#!/usr/bin/env python3
# Reads constant data of the game binary at a virtual address (tables behind jump tables, enum -> GL maps).
# Usage: tools/game-read.py <factorio ELF> <vaddr> <count> [u8|u16|u32|i32|u64|str]
# R_AARCH64_RELATIVE targets are not applied (u64 slots of pointer tables read as 0; use vtable-dump.py).
import struct, sys

path, vaddr, count = sys.argv[1], int(sys.argv[2], 0), int(sys.argv[3], 0)
kind = sys.argv[4] if len(sys.argv) > 4 else 'u32'
data = open(path, 'rb').read()
phoff, = struct.unpack_from('<Q', data, 0x20)
phentsize, phnum = struct.unpack_from('<HH', data, 0x36)
off = None
for i in range(phnum):
    p_type, _, p_offset, p_vaddr, _, p_filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff + i * phentsize)
    if p_type == 1 and p_vaddr <= vaddr < p_vaddr + p_filesz:
        off = p_offset + vaddr - p_vaddr
if off is None:
    sys.exit('address not in a file-backed PT_LOAD')
if kind == 'str':
    for _ in range(count):
        end = data.index(b'\0', off)
        print(repr(data[off:end].decode(errors='replace')))
        off = end + 1
    sys.exit()
fmt = {'u8': 'B', 'u16': 'H', 'u32': 'I', 'i32': 'i', 'u64': 'Q'}[kind]
size = struct.calcsize(fmt)
vals = struct.unpack_from('<%d%s' % (count, fmt), data, off)
print(' '.join(hex(v) if kind != 'i32' else str(v) for v in vals))
