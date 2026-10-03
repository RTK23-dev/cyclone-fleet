_s=open('v2.py').read(); exec(_s[:_s.rindex('if __name__')])
from shapely.geometry import Polygon
def centre(a0,R,a_rim,a_in,tau,S,steps=1600,over=0.12):
    pts=[]; th=0.0; ds=(S+over)/steps
    for i in range(steps+1):
        s=-over+i*ds
        al=math.radians(a_rim+(a_in-a_rim)*(1-math.exp(-max(s,0)/tau)))
        r=R*math.exp(-s)
        pts.append((r,a0+th)); th+=ds/math.tan(al)
    return pts
def band(pts,g):
    L=[];Rr=[]; xy=[(r*math.cos(t),r*math.sin(t)) for r,t in pts]; n=len(xy)-1
    for i,(x,y) in enumerate(xy):
        x2,y2=xy[min(i+1,n)]; x1,y1=xy[max(i-1,0)]; dx,dy=x2-x1,y2-y1; m=math.hypot(dx,dy) or 1
        hw=g*pts[i][0]/2; nx,ny=-dy/m,dx/m
        L.append((x+nx*hw,y+ny*hw)); Rr.append((x-nx*hw,y-ny*hw))
    return Polygon(L+Rr[::-1]).buffer(0)
def worm(n=7,R=90,a_rim=40,a_in=16,tau=0.8,S=6.0,g=0.09,rot=-90,core=0.0):
    disc=Point(0,0).buffer(R,quad_segs=512)
    cuts=unary_union([band(centre(math.radians(rot)+2*math.pi*i/n,R,a_rim,a_in,tau,S),g) for i in range(n)])
    shape=disc.difference(cuts)
    if core: shape=shape.difference(Point(0,0).buffer(core,quad_segs=128))
    return affinity.scale(shape,-1,1,origin=(0,0)).simplify(0.01)
RAD='''<radialGradient id="d" gradientUnits="userSpaceOnUse" cx="100" cy="100" r="92"><stop offset="0" stop-color="#010607"/><stop offset=".12" stop-color="#05262A"/><stop offset=".42" stop-color="#1C9E98"/><stop offset=".78" stop-color="#46DACD"/><stop offset="1" stop-color="#B8F5EC"/></radialGradient>'''
def save_w(name,g,fill='url(#d)',defs=None):
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{defs if defs is not None else RAD+GRAD}</defs><path fill="{fill}" fill-rule="evenodd" d="{path(g)}"/></svg>')

RAD2='''<radialGradient id="d2" gradientUnits="userSpaceOnUse" cx="100" cy="100" r="91"><stop offset="0" stop-color="#000405"/><stop offset=".1" stop-color="#021316"/><stop offset=".3" stop-color="#0B5458"/><stop offset=".58" stop-color="#2EC2B9"/><stop offset=".78" stop-color="#5BE3D6"/><stop offset="1" stop-color="#2AA9A3"/></radialGradient>'''

if __name__=='__main__': exec(sys.argv[1])
