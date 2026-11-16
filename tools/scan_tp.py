#!/usr/bin/env python3
"""Find all MRS/MSR TPIDR_EL0 instructions in executable segments of an aarch64 ELF."""
import struct, sys
def segs(f):
    f.seek(0); h=f.read(64)
    phoff=struct.unpack_from('<Q',h,0x20)[0]; phnum=struct.unpack_from('<H',h,0x38)[0]
    f.seek(phoff)
    for i in range(phnum):
        p=struct.unpack('<IIQQQQQQ',f.read(56))
        yield p
path=sys.argv[1]
with open(path,'rb') as f:
    mrs={}; msr=[]
    for (t,fl,off,va,pa,fsz,msz,al) in list(segs(f)):
        if t!=1 or not (fl&1): continue
        f.seek(off); d=f.read(fsz)
        n=len(d)//4
        for i,w in enumerate(struct.unpack('<%dI'%n,d[:n*4])):
            if (w & ~31)==0xd53bd040: mrs.setdefault(w&31,[]).append(va+i*4)
            elif (w & ~31)==0xd51bd040: msr.append(va+i*4)
    tot=sum(len(v) for v in mrs.values())
    print('mrs tpidr_el0:',tot,'by reg',{k:len(v) for k,v in sorted(mrs.items())})
    print('msr tpidr_el0:',len(msr),[hex(a) for a in msr[:10]])
    if len(sys.argv)>2:
        with open(sys.argv[2],'w') as o:
            for r,v in mrs.items():
                for a in v: o.write('%x %d\n'%(a,r))
