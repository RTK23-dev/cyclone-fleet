import math,sys
from shapely.geometry import Point, LineString, MultiPolygon
from shapely.ops import unary_union
from shapely import affinity
GRAD='<linearGradient id="g" gradientUnits="userSpaceOnUse" x1="30" y1="22" x2="170" y2="178"><stop offset="0" stop-color="#D8FBF5"/><stop offset=".48" stop-color="#41D7CB"/><stop offset="1" stop-color="#17807F"/></linearGradient>'
def curve(a0,r0,r1,sweep,p,steps=600):
    return [((r0+(r1-r0)*((t/steps)**p))*math.cos(a0+sweep*t/steps),(r0+(r1-r0)*((t/steps)**p))*math.sin(a0+sweep*t/steps)) for t in range(steps+1)]
def crossing(r0,r1,sweep,p,R):
    # angle between curve and the rim circle where r=R
    t=((R-r0)/(r1-r0))**(1/p); drdth=(r1-r0)*p*t**(p-1)/sweep
    return math.degrees(math.atan2(drdth,R))
def build(n=3,R=90,eyeR=18,r0=8,r1=100,sweep=190,p=2.4,gap=9,open_r=1.2,rot=-90,mirror=True):
    sw=math.radians(sweep)
    disc=Point(0,0).buffer(R,quad_segs=512)
    cuts=[LineString(curve(math.radians(rot)+2*math.pi*i/n,r0,r1,sw,p)).buffer(gap/2,cap_style=2,quad_segs=64) for i in range(n)]
    s=disc.difference(unary_union(cuts)).difference(Point(0,0).buffer(eyeR,quad_segs=256))
    if open_r: s=s.buffer(-open_r,quad_segs=64).buffer(open_r,quad_segs=64)
    if mirror: s=affinity.scale(s,-1,1,origin=(0,0))
    return s.simplify(0.015), crossing(r0,r1,sw,p,R)
def spark(s,k=0.16,rot=0):
    pts=[(0,-s),(s,0),(0,s),(-s,0)]
    c,sn=math.cos(math.radians(rot)),math.sin(math.radians(rot))
    R=lambda x,y:(100+x*c-y*sn,100+x*sn+y*c)
    d='M%.2f %.2f'%R(*pts[0])
    for i in range(4):
        x0,y0=pts[i];x1,y1=pts[(i+1)%4]
        d+=' C%.2f %.2f %.2f %.2f %.2f %.2f'%(*R(x0*k,y0*k),*R(x1*k,y1*k),*R(x1,y1))
    return d+'Z'
def path(g):
    polys=list(g.geoms) if isinstance(g,MultiPolygon) else [g]
    return ''.join('M'+' L'.join(f'{x+100:.2f} {y+100:.2f}' for x,y in list(r.coords)[:-1])+'Z' for pg in polys for r in [pg.exterior,*pg.interiors])
def save(name,g,sp=None,fill='url(#g)'):
    extra=f'<path fill="{fill}" d="{sp}"/>' if sp else ''
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{GRAD}</defs><path fill="{fill}" fill-rule="evenodd" d="{path(g)}"/>{extra}</svg>')
if __name__=='__main__': exec(sys.argv[1])
