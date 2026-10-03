_s=open('worm2.py').read(); exec(_s[:_s.rindex('if __name__')])
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
P=dict(a_rim=35,a_in=18,g=0.08,tau=1.2)
G=path(worm(S=math.log(90/6),**P)); GM=path(worm(S=math.log(90/14),**P))
DEFS=RAD2+VOID(52,.9)+SHEEN
BODY=f'<circle cx="100" cy="100" r="52" fill="url(#v)"/><path fill="url(#d2)" fill-rule="evenodd" d="{G}"/><path fill="url(#s)" fill-rule="evenodd" d="{G}"/>'
svg=lambda vb,defs,body:f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{vb}"><defs>{defs}</defs>{body}</svg>'
open('cw-mark.svg','w').write(svg('0 0 200 200',DEFS,BODY))
open('cw-mark-mono.svg','w').write(svg('0 0 200 200','',f'<path fill="currentColor" fill-rule="evenodd" d="{GM}"/>'))
for nm,c in [('black','#0A0F10'),('white','#FFFFFF')]:
    open(f'cw-m-{nm}.svg','w').write(svg('0 0 200 200','',f'<path fill="{c}" fill-rule="evenodd" d="{GM}"/>'))
TILE='<linearGradient id="t" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#0A2E34"/><stop offset="1" stop-color="#031114"/></linearGradient>'
open('cw-app-icon.svg','w').write(svg('0 0 200 200',DEFS+TILE,f'<rect width="200" height="200" rx="44" fill="url(#t)"/><g transform="translate(100 100) scale(0.68) translate(-100 -100)">{BODY}</g>'))
def text_path(font,txt,size,x,y,track=0):
    f=TTFont(font); gs=f.getGlyphSet(); cmap=f.getBestCmap(); s=size/f['head'].unitsPerEm; d=''; cx=0
    for ch in txt:
        pen=SVGPathPen(gs); gs[cmap[ord(ch)]].draw(TransformPen(pen,(s,0,0,-s,x+cx,y))); d+=pen.getCommands(); cx+=gs[cmap[ord(ch)]].width*s+track
    return d,cx
for suf,col in [('dark','#EAF7F5'),('light','#062126')]:
    d,w=text_path('Sora600.ttf','Cyclone',96,212,132,-1.0); W=int(212+w+8)
    open(f'cw-lockup-on-{suf}.svg','w').write(svg(f'0 0 {W} 200',DEFS,f'<g transform="translate(20 20) scale(0.8)">{BODY}</g><path fill="{col}" d="{d}"/>'))
print('ok')
