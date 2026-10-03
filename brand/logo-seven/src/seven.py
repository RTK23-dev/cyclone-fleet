exec(open('v2.py').read().replace("if __name__=='__main__': exec(sys.argv[1])",""))
from shapely.geometry import Polygon
def star_poly(s,k=0.16,rot=0,steps=60):
    pts=[(0,-s),(s,0),(0,s),(-s,0)]; out=[]
    for i in range(4):
        P0=pts[i];P3=pts[(i+1)%4];P1=(P0[0]*k,P0[1]*k);P2=(P3[0]*k,P3[1]*k)
        for j in range(steps):
            t=j/steps;a=(1-t)**3;b=3*(1-t)**2*t;c=3*(1-t)*t*t;d=t**3
            out.append((a*P0[0]+b*P1[0]+c*P2[0]+d*P3[0],a*P0[1]+b*P1[1]+c*P2[1]+d*P3[1]))
    poly=Polygon(out)
    return affinity.rotate(poly,rot,origin=(0,0))
LIGHT='<radialGradient id="L" gradientUnits="userSpaceOnUse" cx="100" cy="100" r="58"><stop offset="0" stop-color="#FFFFFF"/><stop offset=".35" stop-color="#F0FFFC"/><stop offset="1" stop-color="#7FE9DE"/></radialGradient>'
def compose(name,n=7,gap=5.5,eyeR=16,r0=13,sweep=150,p=2.6,star=58,k=0.12,halo=4,starfill='url(#L)',open_r=0.3,rot=-90,knock=False):
    g,ang=build(n=n,R=90,eyeR=eyeR,r0=r0,r1=92,sweep=sweep,p=p,gap=gap,open_r=open_r,rot=rot)
    st=star_poly(star,k)
    arms=g.difference(st.buffer(halo,join_style=2,mitre_limit=5)) if halo else g
    arms=arms.buffer(-0.3).buffer(0.3)
    body=f'<path fill="url(#g)" fill-rule="evenodd" d="{path(arms)}"/>'
    if not knock: body+=f'<path fill="{starfill}" d="{path(st)}"/>'
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{GRAD}{LIGHT}</defs>{body}</svg>')
    return ang

def sstar(s,q=0.62,steps=720,rot=0):
    pts=[]
    for i in range(steps):
        t=2*math.pi*i/steps; c,sn=math.cos(t),math.sin(t)
        pts.append((s*math.copysign(abs(c)**(2/q),c), s*math.copysign(abs(sn)**(2/q),sn)))
    return affinity.rotate(Polygon(pts),rot,origin=(0,0))
def compose2(name,n=7,gap=5.5,eyeR=14,r0=11,sweep=150,p=2.6,star=56,q=0.62,halo_scale=1.14,halo_px=1.5,open_r=0.3,rot=-90,starfill='url(#L)'):
    g,_=build(n=n,R=90,eyeR=eyeR,r0=r0,r1=92,sweep=sweep,p=p,gap=gap,open_r=open_r,rot=rot)
    st=sstar(star,q)
    arms=g
    if halo_scale: arms=arms.difference(affinity.scale(st,halo_scale,halo_scale,origin=(0,0)).buffer(halo_px))
    arms=arms.buffer(-0.3).buffer(0.3)
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{GRAD}{LIGHT}</defs><path fill="url(#g)" fill-rule="evenodd" d="{path(arms)}"/><path fill="{starfill}" d="{path(st)}"/></svg>')

if __name__=='__main__': exec(sys.argv[1])
