"""Bakes the AI page's scene: a tiny video that carries, per digit cell, the colour and light the rain should show.
Pixel = colour * light, with colour's brightest channel at 1, so the shader reads light = max(r, g, b) and
colour = rgb / light. Same auto-exposure, local contrast, saturation and whitening as render.py's colour mode.
Usage: python3 bake.py SRC OUT.mp4 [W H CRF]"""
import subprocess, sys, numpy as np
FF = '/usr/local/lib/python3.11/dist-packages/imageio_ffmpeg/binaries/ffmpeg-linux-x86_64-v7.0.2'
SRC, OUT = sys.argv[1], sys.argv[2]
W = int(sys.argv[3]) if len(sys.argv) > 3 else 80
H = int(sys.argv[4]) if len(sys.argv) > 4 else 144
CRF = sys.argv[5] if len(sys.argv) > 5 else '24'

def box_blur(a, k):
    c = np.cumsum(np.pad(a, ((k, k), (0, 0)), mode='edge'), axis=0); a = (c[2 * k:] - c[:-2 * k]) / (2 * k)
    c = np.cumsum(np.pad(a, ((0, 0), (k, k)), mode='edge'), axis=1); return (c[:, 2 * k:] - c[:, :-2 * k]) / (2 * k)

dec = subprocess.Popen([FF, '-loglevel', 'error', '-i', SRC, '-vf', f'scale={W}:{H}:flags=area', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'], stdout=subprocess.PIPE)
enc = subprocess.Popen([FF, '-loglevel', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W}x{H}', '-r', '30', '-i', '-',
                        '-an', '-c:v', 'libx264', '-preset', 'veryslow', '-crf', CRF, '-g', '60', '-pix_fmt', 'yuv420p',
                        '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709', '-color_range', 'tv',
                        '-movflags', '+faststart', OUT], stdin=subprocess.PIPE)
n = 0
while True:
    raw = dec.stdout.read(W * H * 3)
    if len(raw) < W * H * 3: break
    src = np.frombuffer(raw, np.uint8).reshape(H, W, 3).astype(np.float32) / 255
    lum = src @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    lum = np.clip(lum / max(float(np.percentile(lum, 99.3)), 0.2), 0, 1)
    mx = src.max(2)
    lum = np.maximum(lum, 0.6 * mx / max(float(np.percentile(mx, 99.3)), 0.2))
    lum = np.clip(lum + 0.8 * (lum - box_blur(lum, 3)), 0, 1) ** 0.85
    vivid = src / np.maximum(mx, 0.08)[..., None]
    vivid = np.clip(0.5 + (vivid - 0.5) * 1.15, 0, 1)
    col = vivid * (1 - 0.15 * lum[..., None]) + 0.15 * lum[..., None]
    col = col / np.maximum(col.max(2, keepdims=True), 1e-3)          # brightest channel = 1
    out = col * lum[..., None]
    enc.stdin.write((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8).tobytes())
    n += 1
enc.stdin.close(); enc.wait(); dec.kill()
print('baked', n, 'frames')
