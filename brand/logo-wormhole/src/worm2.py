_s=open('worm.py').read(); exec(_s[:_s.rindex('if __name__')])
def VOID(r=62,o1=.85): return f'''<radialGradient id="v" gradientUnits="userSpaceOnUse" cx="100" cy="100" r="{r}"><stop offset="0" stop-color="#000405"/><stop offset=".35" stop-color="#011012" stop-opacity="1"/><stop offset=".7" stop-color="#0A4D50" stop-opacity="{o1}"/><stop offset="1" stop-color="#1C9E98" stop-opacity="0"/></radialGradient>'''
def save_v(name,g,vr=62,o1=.85,fill='url(#d2)'):
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{RAD2}{VOID(vr,o1)}</defs><circle cx="100" cy="100" r="{vr}" fill="url(#v)"/><path fill="{fill}" fill-rule="evenodd" d="{path(g)}"/></svg>')

SHEEN='''<linearGradient id="s" gradientUnits="userSpaceOnUse" x1="25" y1="18" x2="175" y2="182"><stop offset="0" stop-color="#FFFFFF" stop-opacity=".28"/><stop offset=".45" stop-color="#FFFFFF" stop-opacity="0"/><stop offset=".62" stop-color="#000000" stop-opacity="0"/><stop offset="1" stop-color="#001214" stop-opacity=".30"/></linearGradient>'''
def save_s(name,g,vr=52,o1=.9,sheen=True):
    d=path(g)
    extra=f'<path fill="url(#s)" fill-rule="evenodd" d="{d}"/>' if sheen else ''
    open(name,'w').write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 200"><defs>{RAD2}{VOID(vr,o1)}{SHEEN}</defs><circle cx="100" cy="100" r="{vr}" fill="url(#v)"/><path fill="url(#d2)" fill-rule="evenodd" d="{d}"/>{extra}</svg>')

if __name__=='__main__': exec(sys.argv[1])
