# Cyclone logo generator. Needs: pip install shapely fonttools; Sora600.ttf (Google Fonts) for the lockups.
# Writes the SVGs in this folder from the exact construction below.
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

from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
ROT=-78.5; SPARK=15.5
g,_=build(r1=92,p=3.2,sweep=170,eyeR=24,r0=19.5,open_r=0.4,rot=ROT)
MARK=path(g); SP=spark(SPARK)
def text_path(font,txt,size,x,y,track=0):
    f=TTFont(font); gs=f.getGlyphSet(); cmap=f.getBestCmap(); upm=f['head'].unitsPerEm; s=size/upm
    d=''; cx=0
    for ch in txt:
        gn=cmap[ord(ch)]; pen=SVGPathPen(gs)
        tp=TransformPen(pen,(s,0,0,-s,x+cx,y)); gs[gn].draw(tp); d+=pen.getCommands(); cx+=gs[gn].width*s+track
    return d,cx
def mark_svg(fill='url(#g)',defs=GRAD,spfill=None):
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{defs}</defs><path fill="{fill}" fill-rule="evenodd" d="{MARK}"/><path fill="{spfill or fill}" d="{SP}"/></svg>'
open('cyclone-mark.svg','w').write(mark_svg())
open('cyclone-mark-mono.svg','w').write(mark_svg(fill='currentColor',defs=''))
for name,col in [('mono-teal','#41D7CB'),('mono-black','#0A0F10'),('mono-white','#FFFFFF')]:
    open(f'm-{name}.svg','w').write(mark_svg(fill=col,defs=''))
# app icon tile 200x200: deep gradient tile, mark at 64%
TILE='<linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A2E34"/><stop offset="1" stop-color="#031114"/></linearGradient>'
open('cyclone-app-icon.svg','w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{GRAD}{TILE}</defs><rect width="200" height="200" rx="44" fill="url(#t)"/><g transform="translate(100 100) scale(0.66) translate(-100 -100)"><path fill="url(#g)" fill-rule="evenodd" d="{MARK}"/><path fill="url(#g)" d="{SP}"/></g></svg>')
# lockups
for fname,label,track,wcol in [('Sora600.ttf','sora',-1.0,'#EAF7F5'),('Inter600.ttf','inter',-2.4,'#EAF7F5'),('Sora500.ttf','sora500',-0.6,'#EAF7F5')]:
    d,w=text_path(fname,'Cyclone',96,230,132,track)
    W=int(230+w+20)
    for bg,col,suffix in [('#041519',wcol,'dark'),('#F4F7F7','#062126','light')]:
        open(f'lock-{label}-{suffix}.svg','w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} 200"><defs>{GRAD}</defs><rect width="{W}" height="200" fill="{bg}"/><g transform="translate(20 20) scale(0.8)"><path fill="url(#g)" fill-rule="evenodd" d="{MARK}"/><path fill="url(#g)" d="{SP}"/></g><path fill="{col}" d="{d}"/></svg>')
print('ok')

# final lockups: Sora 600, tighter gap, transparent background
for suffix,col in [('dark','#EAF7F5'),('light','#062126')]:
    d,w=text_path('Sora600.ttf','Cyclone',96,212,132,-1.0)
    W=int(212+w+8)
    open(f'cyclone-lockup-on-{suffix}.svg','w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} 200"><defs>{GRAD}</defs><g transform="translate(20 20) scale(0.8)"><path fill="url(#g)" fill-rule="evenodd" d="{MARK}"/><path fill="url(#g)" d="{SP}"/></g><path fill="{col}" d="{d}"/></svg>')
