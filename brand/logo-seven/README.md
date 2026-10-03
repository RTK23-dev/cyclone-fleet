# Cyclone logo: seven panels, shining star

**The mark:**
- A perfect disc with seven spiral wind-trails turning counter-clockwise.
- A large four-point star of light blazes from the eye, bright white at its core and fading to teal at the tips, so the light seems to pour through the spinning storm.

| File | Use |
|---|---|
| `cyclone-seven-mark.svg` | Colour master (brand gradient + light gradient). Dark and light backgrounds. |
| `cyclone-seven-mark-mono.svg` | One colour (`currentColor`). The star is cut out, so the background shines through, inside a small collar. |
| `cyclone-seven-app-icon.svg` | App icon tile. |
| `cyclone-seven-lockup-on-dark.svg` / `-on-light.svg` | Mark + "Cyclone" (Sora SemiBold, outlined). |
| `cyclone-seven-sheet.png` | Overview. |
| `src/` | Generator scripts. Run from `src/` with `python final7.py` (needs shapely, fonttools, `Sora600.ttf`). |

## Construction (200 × 200 grid)

- **Disc:** radius 90. No notches, so the silhouette is a true circle.
- **Wind trails:** seven, 51.4° apart.
  - Each centreline is r(t) = 10 + 76·t^2.2 over a 240° sweep, mirrored to turn counter-clockwise.
  - Each is 8 wide, with a sine taper over the first 14% and a cosine taper over the last 55%, so it fades out like a wind trail before the rim.
- **Star:** a superellipse |x|^q + |y|^q = 64^q with q = 0.57 (upright).
  - Fill is a radial gradient: `#FFFFFF` → `#F0FFFC` (35%) → `#7FE9DE` (r = 58).
- **One colour:** the trails stop 6 units short of the star, and the star is cut out.
- **Gradient:** `#D8FBF5` → `#41D7CB` → `#17807F` (top-left to bottom-right).
