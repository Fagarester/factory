# The gear menu's Factorio skin, drawn as FactorioSkin cuts it (a check of the sprite places without a phone).
# python3 mock.py <game>/data/core/graphics/gui-new.png out.png  (the picture is the game's art: keep it out of git)
import os, sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from crop import read_png, write_png
W,H,S=read_png(sys.argv[1])
u=1.8
cw,chh=900,520
img=bytearray([90,70,40,255]*cw*chh)
def put(x,y,c):
    if 0<=x<cw and 0<=y<chh:
        r,g,b,a=c; k=(y*cw+x)*4
        for i in range(3): img[k+i]=(c[i]*a+img[k+i]*(255-a))//255
def blit(sx,sy,sw,sh,l,t,r,b):
    l,t,r,b=int(l),int(t),int(r),int(b)
    if r<=l or b<=t: return
    for y in range(t,b):
        yy=sy+min(sh-1,int((y-t)*sh/(b-t)))
        for x in range(l,r):
            xx=sx+min(sw-1,int((x-l)*sw/(r-l)))
            k=(yy*W+xx)*4; put(x,y,tuple(S[k:k+4]))
def nine(x,y,l,t,r,b,c=8,cx=None,cy=None):
    cx=x+c if cx is None else cx; cy=y+c if cy is None else cy
    d=min(c*u,(r-l)/2,(b-t)/2)
    blit(x,y,c,c,l,t,l+d,t+d); blit(x+c+1,y,c,c,r-d,t,r,t+d); blit(x,y+c+1,c,c,l,b-d,l+d,b); blit(x+c+1,y+c+1,c,c,r-d,b-d,r,b)
    blit(x+c,y,1,c,l+d,t,r-d,t+d); blit(x+c,y+c+1,1,c,l+d,b-d,r-d,b); blit(x,y+c,c,1,l,t+d,l+d,b-d); blit(x+c+1,y+c,c,1,r-d,t+d,r,b-d)
    blit(cx,cy,1,1,l+d,t+d,r-d,b-d)
nine(0,0,20,20,880,500)                         # frame
s=8*u
x=200
while x<820:                                     # grip
    blit(192,8,8,7,x,30,x+s,30+s*7/8); blit(200,8,8,8,x,30+s*7/8,x+s,30+2*s); blit(208,8,8,8,x,30+2*s,x+s,30+3*s); x+=s
nine(0,0,830,28,870,68)                          # close button
nine(136,0,36,80,200,120); nine(102,0,206,80,280,120); nine(102,0,286,80,420,120)   # tabs
nine(17,0,36,124,864,484,cx=76,cy=8)             # inner
for i,(sx,mark) in enumerate([(56,True),(0,False),(28,'dis')]):
    y=150+i*50
    blit(sx,132,28,28,60,y,60+28*u,y+28*u)
    if mark is True: blit(112,132,28,28,60,y,60+28*u,y+28*u)
    if mark=='dis': blit(456,188,28,28,60,y,60+28*u,y+28*u)
blit(72,160,24,24,60,310,60+24*u,310+24*u); blit(0,160,24,24,60,360,60+24*u,360+24*u)
# slider
y=420; h=8*u; L,R=300,800
blit(56,72,8,8,L,y,L+h,y+h); blit(64,72,1,8,L+h,y,R-h,y+h); blit(65,72,8,8,R-h,y,R,y+h)
nine(73,72,L,y,500,y+h)
blit(64,48,40,24,500-20*u,y+h/2-12*u,500+20*u,y+h/2+12*u)
nine(0,17,300,150,560,200)                       # button
nine(68,17,600,150,800,200)                      # green button
write_png(sys.argv[2],cw,chh,img)
