_s=open('seven.py').read(); exec(_s[:_s.rindex('if __name__')])
def slit(a0,r0,r1,sweep,p,w,taper_in=0.12,taper_out=0.35,steps=500):
    pts=curve(a0,r0,r1,sweep,p,steps)
    L=[];Rr=[]
    for i,(x,y) in enumerate(pts):
        t=i/steps
        x2,y2=pts[min(i+1,steps)]; x1,y1=pts[max(i-1,0)]
        dx,dy=x2-x1,y2-y1; n=math.hypot(dx,dy) or 1; nx,ny=-dy/n,dx/n
        f=1.0
        if t<taper_in: f=math.sin(t/taper_in*math.pi/2)
        if t>1-taper_out: f=math.cos((t-(1-taper_out))/taper_out*math.pi/2)
        hw=w/2*f
        L.append((x+nx*hw,y+ny*hw)); Rr.append((x-nx*hw,y-ny*hw))
    return Polygon(L+Rr[::-1]).buffer(0)
def compose4(name,n=7,gap=6,R=90,r0=10,r1=84,sweep=250,p=2.2,star=60,q=0.6,rot=-90,tin=0.1,tout=0.38,mono=None,clear=None):
    sw=math.radians(sweep)
    disc=Point(0,0).buffer(R,quad_segs=512)
    st=sstar(star,q)
    cuts=unary_union([slit(math.radians(rot)+2*math.pi*i/n,r0,r1,sw,p,gap,tin,tout) for i in range(n)])
    if clear: cuts=cuts.difference(st.buffer(clear,quad_segs=64))
    arms=affinity.scale(disc.difference(cuts),-1,1,origin=(0,0)).simplify(0.015)
    if mono:
        body=f'<path fill="{mono}" fill-rule="evenodd" d="{path(arms.difference(st).buffer(-0.25).buffer(0.25))}"/>'; defs=''
    else:
        body=f'<path fill="url(#g)" fill-rule="evenodd" d="{path(arms)}"/><path fill="url(#L)" d="{path(st)}"/>'; defs=GRAD+LIGHT
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{defs}</defs>{body}</svg>')
if __name__=='__main__': exec(sys.argv[1])
