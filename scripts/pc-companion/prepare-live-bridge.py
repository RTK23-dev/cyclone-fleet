"""Reproducible tunnel download. No runtime shell/bootstrap dependency."""
from pathlib import Path
import hashlib
import urllib.request
ROOT = Path(__file__).resolve().parents[2]
URL = 'https://github.com/cloudflare/cloudflared/releases/download/2026.8.3/cloudflared-windows-amd64.exe'
SHA = '83e726ed18ea78c5ad5213c4c3a3a27051393950d2bc8ed4de69bec12d14eaae'
target = ROOT / 'apps/pc-companion/src-tauri/resources/live-phone/cloudflared.exe'
target.parent.mkdir(parents=True, exist_ok=True)
if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != SHA:
    with urllib.request.urlopen(URL, timeout=90) as r: data = r.read(100 * 1024 * 1024)
    if hashlib.sha256(data).hexdigest() != SHA: raise SystemExit('cloudflared checksum mismatch')
    target.write_bytes(data)
print('Pinned Live Phone HTTPS transport verified')
