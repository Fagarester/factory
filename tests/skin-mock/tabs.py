# Mock of the gear menu's network page after the change: main tabs top-aligned, sub-tabs over their own frame.
import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from crop import read_png, write_png
# python3 tabs.py <game>/data/core/graphics/gui-new.png out.png  (the picture is the game's art: keep it out of git)
W,H,S=read_png(sys.argv[1])
e=8*1.8
u=1.8; cw,chh=760,330
img=bytearray([20,20,20,255]*cw*chh)
def put(x,y,c):
    if 0<=x<cw and 0<=y<chh:
        k=(y*cw+x)*4
        for i in range(3): img[k+i]=(c[i]*c[3]+img[k+i]*(255-c[3]))//255
def blit(sx,sy,sw,sh,l,t,r,b):
    l,t,r,b=int(l),int(t),int(r),int(b)
    if r<=l or b<=t: return
    for y in range(t,b):
        yy=sy+min(sh-1,int((y-t)*sh/(b-t)))
        for x in range(l,r):
            xx=sx+min(sw-1,int((x-l)*sw/(r-l))); k=(yy*W+xx)*4; put(x,y,tuple(S[k:k+4]))
def px_at(x,y): k=(y*W+x)*4; return tuple(S[k:k+4])
def tabbed(l,t,r,b):  # tabbed_pane_graphical_set (top, centre, bottom) + sides: the top edge turned (left), the bottom one (right); mitred corners
    l,t,r,b=int(l),int(t),int(r),int(b); ee=int(e)
    top=[px_at(76,i) for i in range(8)]; bot=[px_at(76,9+i) for i in range(8)]; c=px_at(76,8)
    for y in range(t,b):
        for x in range(l,r):
            dt,db,dl,dr=y-t,b-1-y,x-l,r-1-x; m=min(dt,db,dl,dr)
            if m>=ee: put(x,y,c); continue
            i=min(7,int(m*8/ee))
            if m==dt: put(x,y,top[i])
            elif m==dl: put(x,y,top[i])
            elif m==db: put(x,y,bot[7-i])
            else: put(x,y,bot[7-i])
def nine(x,y,l,t,r,b,c=8,cx=None,cy=None):
    cx=x+c if cx is None else cx; cy=y+c if cy is None else cy
    d=min(c*u,(r-l)/2,(b-t)/2)
    blit(x,y,c,c,l,t,l+d,t+d); blit(x+c+1,y,c,c,r-d,t,r,t+d); blit(x,y+c+1,c,c,l,b-d,l+d,b); blit(x+c+1,y+c+1,c,c,r-d,b-d,r,b)
    blit(x+c,y,1,c,l+d,t,r-d,t+d); blit(x+c,y+c+1,1,c,l+d,b-d,r-d,b); blit(x,y+c,c,1,l,t+d,l+d,b-d); blit(x+c+1,y+c,c,1,r-d,t+d,r,b-d)
    blit(cx,cy,1,1,l+d,t+d,r-d,b-d)
th=50
nine(0,0,10,10,750,320)                                   # window frame
top=30; ptop=top+th-e                                       # pages pulled up by 8u
tabbed(30,ptop,730,310)                                    # pages
x=30+12*u
for w,ch in ((120,0),(80,0),(160,0),(110,1)):
    nine(136 if ch else 102,0,x,top,x+w,top+th+(e if ch else 0)); x+=w+8
st=ptop+e+12*u                                              # sub-tabs (inside the pages' padding)
btop=st+th-e
tabbed(60,btop,700,300)                                     # the network page's own frame
nine(136,0,60+12*u,st,190+12*u,st+th+e); nine(102,0,196+12*u,st,330+12*u,st+th)
nine(0,17,60+e+5,btop+12*u+e,700-e-5,btop+12*u+e+45)        # "Создать" button
write_png(sys.argv[2],cw,chh,img)
