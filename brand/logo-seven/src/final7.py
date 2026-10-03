_s=open('seven3.py').read(); exec(_s[:_s.rindex('if __name__')])
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
KW=dict(sweep=240,p=2.2,gap=8,tin=0.14,tout=0.55,star=64,q=0.57,r1=86)
def geom(clear=None,n=7,gap=8,R=90,r0=10,r1=86,sweep=240,p=2.2,star=64,q=0.57,rot=-90,tin=0.14,tout=0.55):
    sw=math.radians(sweep); disc=Point(0,0).buffer(R,quad_segs=512); st=sstar(star,q)
    cuts=unary_union([slit(math.radians(rot)+2*math.pi*i/n,r0,r1,sw,p,gap,tin,tout) for i in range(n)])
    if clear: cuts=cuts.difference(st.buffer(clear,quad_segs=64))
    return affinity.scale(disc.difference(cuts),-1,1,origin=(0,0)).simplify(0.015), st
ARMS,ST=geom(); ARMS_M,_=geom(clear=6)
MONO=path(ARMS_M.difference(ST).buffer(-0.25).buffer(0.25))
COL=f'<path fill="url(#g)" fill-rule="evenodd" d="{path(ARMS)}"/><path fill="url(#L)" d="{path(ST)}"/>'
svg=lambda vb,defs,body:f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}"><defs>{defs}</defs>{body}</svg>'
open('c7-mark.svg','w').write(svg('0 0 200 200',GRAD+LIGHT,COL))
open('c7-mark-mono.svg','w').write(svg('0 0 200 200','',f'<path fill="currentColor" fill-rule="evenodd" d="{MONO}"/>'))
for nm,c in [('black','#0A0F10'),('white','#FFFFFF'),('teal','#41D7CB')]:
    open(f'c7-m-{nm}.svg','w').write(svg('0 0 200 200','',f'<path fill="{c}" fill-rule="evenodd" d="{MONO}"/>'))
TILE='<linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A2E34"/><stop offset="1" stop-color="#031114"/></linearGradient>'
open('c7-app-icon.svg','w').write(svg('0 0 200 200',GRAD+LIGHT+TILE,f'<rect width="200" height="200" rx="44" fill="url(#t)"/><g transform="translate(100 100) scale(0.68) translate(-100 -100)">{COL}</g>'))
def text_path(font,txt,size,x,y,track=0):
    f=TTFont(font); gs=f.getGlyphSet(); cmap=f.getBestCmap(); s=size/f['head'].unitsPerEm; d=''; cx=0
    for ch in txt:
        pen=SVGPathPen(gs); gs[cmap[ord(ch)]].draw(TransformPen(pen,(s,0,0,-s,x+cx,y))); d+=pen.getCommands(); cx+=gs[cmap[ord(ch)]].width*s+track
    return d,cx
for suf,col in [('dark','#EAF7F5'),('light','#062126')]:
    d,w=text_path('Sora600.ttf','Cyclone',96,212,132,-1.0); W=int(212+w+8)
    open(f'c7-lockup-on-{suf}.svg','w').write(svg(f'0 0 {W} 200',GRAD+LIGHT,f'<g transform="translate(20 20) scale(0.8)">{COL}</g><path fill="{col}" d="{d}"/>'))
print('ok')
