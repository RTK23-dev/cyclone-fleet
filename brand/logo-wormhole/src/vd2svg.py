# Converts the Android vector drawables back to SVG (paths, groups, aapt gradients) to check them visually.
import sys,re,xml.etree.ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
def col(c):
    c=c.lstrip('#'); a=int(c[:2],16)/255 if len(c)==8 else 1; rgb=c[-6:]; return '#'+rgb,a
n=[0]
def conv(path):
    root=ET.parse(path).getroot(); vw=root.get(A+'viewportWidth'); vh=root.get(A+'viewportHeight'); defs=[]
    def walk(el):
        out=''
        for ch in el:
            tag=ch.tag
            if tag=='group':
                sx=ch.get(A+'scaleX','1');sy=ch.get(A+'scaleY','1');tx=ch.get(A+'translateX','0');ty=ch.get(A+'translateY','0')
                out+=f'<g transform="translate({tx} {ty}) scale({sx} {sy})">'+walk(ch)+'</g>'
            elif tag=='path':
                fill=ch.get(A+'fillColor'); rule='evenodd' if ch.get(A+'fillType')=='evenOdd' else 'nonzero'
                for at in ch.findall('{http://schemas.android.com/aapt}attr'):
                    g=at.find('gradient'); n[0]+=1; gid=f'g{n[0]}'
                    stops=''.join(f'<stop offset="{i.get(A+"offset")}" stop-color="{col(i.get(A+"color"))[0]}" stop-opacity="{col(i.get(A+"color"))[1]:.3f}"/>' for i in g.findall('item'))
                    if g.get(A+'type')=='radial':
                        defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g.get(A+"centerX")}" cy="{g.get(A+"centerY")}" r="{g.get(A+"gradientRadius")}">{stops}</radialGradient>')
                    else:
                        defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g.get(A+"startX")}" y1="{g.get(A+"startY")}" x2="{g.get(A+"endX")}" y2="{g.get(A+"endY")}">{stops}</linearGradient>')
                    fill=f'url(#{gid})'
                if fill and fill.startswith('#'):
                    c,a=col(fill); fill=f'{c}" fill-opacity="{a:.3f}'
                d=ch.get(A+'pathData')
                out+=f'<path fill="{fill}" fill-rule="{rule}" d="{d}"/>'
        return out
    body=walk(root)
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}"><defs>{"".join(defs)}</defs>{body}</svg>'
for src,dst in zip(sys.argv[1::2],sys.argv[2::2]): open(dst,'w').write(conv(src))
