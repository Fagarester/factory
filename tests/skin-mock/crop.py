import zlib, struct, sys
def read_png(p):
    d=open(p,'rb').read(); pos=8; idat=b''; w=h=0
    while pos<len(d):
        ln=struct.unpack('>I',d[pos:pos+4])[0]; t=d[pos+4:pos+8]; c=d[pos+8:pos+8+ln]; pos+=12+ln
        if t==b'IHDR': w,h,bd,ct=struct.unpack('>IIBB',c[:10]); assert bd==8 and ct in (6,4),(bd,ct); ch=4 if ct==6 else 2
        elif t==b'IDAT': idat+=c
    raw=zlib.decompress(idat); stride=w*ch; out=bytearray(); prev=bytearray(stride); i=0
    for y in range(h):
        f=raw[i]; i+=1; line=bytearray(raw[i:i+stride]); i+=stride
        for x in range(stride):
            a=line[x-ch] if x>=ch else 0; b=prev[x]; cc=prev[x-ch] if x>=ch else 0
            if f==1: line[x]=(line[x]+a)&255
            elif f==2: line[x]=(line[x]+b)&255
            elif f==3: line[x]=(line[x]+(a+b)//2)&255
            elif f==4:
                pp=a+b-cc; pa,pb,pc=abs(pp-a),abs(pp-b),abs(pp-cc)
                line[x]=(line[x]+(a if pa<=pb and pa<=pc else b if pb<=pc else cc))&255
        out+=line; prev=line
    if ch==2:  # gray+alpha -> rgba
        o2=bytearray()
        for k in range(0,len(out),2): o2+=bytes([out[k],out[k],out[k],out[k+1]])
        out=o2
    return w,h,out
def write_png(p,w,h,px):
    raw=b''.join(b'\0'+bytes(px[y*w*4:(y+1)*w*4]) for y in range(h))
    def chunk(t,c): return struct.pack('>I',len(c))+t+c+struct.pack('>I',zlib.crc32(t+c)&0xffffffff)
    open(p,'wb').write(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw))+chunk(b'IEND',b''))
if __name__=='__main__':
    src,dst,x0,y0,cw,chh,s=sys.argv[1],sys.argv[2],*map(int,sys.argv[3:8])
    w,h,px=read_png(src); out=bytearray()
    bg=(60,60,60)
    for y in range(chh*s):
        for x in range(cw*s):
            k=((y0+y//s)*w+(x0+x//s))*4; r,g,b,a=px[k:k+4]
            out+=bytes([(r*a+bg[0]*(255-a))//255,(g*a+bg[1]*(255-a))//255,(b*a+bg[2]*(255-a))//255,255])
    write_png(dst,cw*s,chh*s,out)
