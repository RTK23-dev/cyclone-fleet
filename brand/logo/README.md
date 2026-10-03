# Cyclone logo

**The mark:** a storm seen from above.
- A solid disc is cut by three spiral gaps that turn counter-clockwise, like a northern-hemisphere cyclone.
- The gaps flow out of a calm eye.
- In the eye sits a four-point spark: the intelligence at the centre of the storm.

| File | Use |
|---|---|
| `cyclone-mark.svg` | The mark, brand gradient. Works on dark and light. |
| `cyclone-mark-mono.svg` | One colour (`currentColor`): set `color` to teal `#41D7CB`, black `#0A0F10` or white. |
| `cyclone-app-icon.svg` | App icon: deep teal tile with the mark at 66%. |
| `cyclone-lockup-on-dark.svg` / `-on-light.svg` | Mark + "Cyclone" in Sora SemiBold, converted to outlines (no font needed). |
| `cyclone-logo-sheet.png` | Overview of the whole system. |
| `generate.py` | Rebuilds every SVG from the construction below. |

## Construction (200 × 200 grid)

- **Disc:** radius 90, centred.
- **Eye:** radius 24.
- **Gaps:** three, 120° apart, each 9 wide.
  - Each gap's centreline is r(t) = 19.5 + (92 − 19.5)·t^3.2 over a 170° sweep, mirrored so it turns counter-clockwise.
  - It starts half a gap inside the eye. That gives knife-sharp inner tips and a clean eye.
  - It leaves the rim at about 40°. That gives crisp notches and a true circle.
- **Rotation:** one notch sits exactly at 6 o'clock, for a stable, tripod stance.
- **Spark:** a four-point star of radius 15.5 with smooth concave sides, centred in the eye.
- **Gradient:** `#D8FBF5` → `#41D7CB` → `#17807F`, from top-left to bottom-right.
- **Background tones:** `#041519` (canvas), tile `#0A2E34` → `#031114`.
- **Smallest size:** the mark stays legible at 20 px; the spark drops out below about 48 px by design.
