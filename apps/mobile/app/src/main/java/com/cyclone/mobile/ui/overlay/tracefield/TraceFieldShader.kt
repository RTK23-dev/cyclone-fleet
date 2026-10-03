package com.cyclone.mobile.ui.overlay.tracefield

/**
 * AGSL for the Trace Field. Every cell of one full grid holds a cycling digit; pixels between digits
 * exit before any field maths. The lit amount comes from the attention lens (target, ripple, scan) and
 * the Cyclone Tide scene layer, a slow picture drawn with digit brightness (see TraceSceneDirector).
 *
 * Atlas rows: 0 = glyph core, 1 = outline halo, 2 = soft (blurred) glyph for depth of field.
 * The backdrop child is a coarse colour grid averaged from Cyclone's own observation screenshots;
 * overlays cannot blend against other apps' pixels, so this is how the field adapts to them.
 */
internal object TraceFieldShader {
    /** Hex digits carry the page fingerprint; the four marks are texture. Order is the atlas order. */
    const val GLYPHS = "0123456789ABCDEF·:+/"
    const val ATLAS_ROWS = 3

    val SOURCE = """
uniform shader atlas;
uniform shader backdrop;
uniform float2 res;
uniform float2 cell;
uniform float2 gridSize;
uniform float backdropOn;
uniform float style;
uniform float glyphCount;
uniform float clock;
uniform float seed;
uniform float4 lens;
uniform float lensCorner;
uniform float lensSoft;
uniform float intensity;
uniform float4 ripple;
uniform float2 scan;
uniform float flow;
uniform float rain;
uniform float scramble;
uniform float edge;
uniform float edgeHead;
uniform float warmth;
uniform float opacity;
uniform float focus;
uniform float flowTime;
uniform float4 excl;
uniform float exclRadius;
uniform float exclFeather;
uniform float sceneA;
uniform float sceneB;
uniform float sceneFront;
uniform float sceneRadial;
uniform float sceneLevel;
layout(color) uniform half4 tint;
layout(color) uniform half4 hot;
layout(color) uniform half4 warm;
layout(color) uniform half4 ink;
layout(color) uniform half4 accent;

// Seeded hash: only for effects that may legitimately vary per page (none flicker per frame).
float h21(float2 p) {
    p = fract(p * float2(123.34, 456.21) + float2(seed * 0.0137, seed * 0.0071));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

float atlasA(float g, float2 local, float row) {
    float2 l = clamp(local, float2(0.5), cell - 0.5);
    return float(atlas.eval(float2(g * cell.x + l.x, row * cell.y + l.y)).a);
}

// Seed-free hash. Layout, timing and glyph identity all use it, so a new page fingerprint never
// reshuffles the whole field in one frame (that full-field jump read as violent flicker).
float n21(float2 p) {
    p = fract(p * float2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}

// Resolves the glyph cell under xy. Every cell of the grid holds a digit, so the highlight can
// light up any spot on screen and reads as one clean, even field.
void cellAt(float2 xy, float2 size, float layer, float shift,
            out float2 local, out float g, out float gPrev, out float age, out float2 id, out float2 centre) {
    float col = floor(xy.x / size.x);
    float colRate = 0.6 + 0.8 * n21(float2(col, layer * 17.0 + 3.0));
    float2 p = float2(xy.x, xy.y - shift * colRate);
    float2 c = floor(p / size);
    id = c;
    centre = (c + 0.5) * size + float2(0.0, shift * colRate);
    local = (p - c * size) * (cell / size);
    // Calm cadence: each digit changes every ~1-4 s (recovery only doubles it) and cross-fades,
    // so the field breathes instead of strobing.
    float rate = 0.25 + 0.7 * n21(c + 3.1) + scramble * 0.9;
    float phase = clock * rate + n21(c + 7.7) * 10.0;
    float tick = floor(phase);
    age = phase - tick;
    g = floor(n21(c + tick * 0.618) * glyphCount);
    gPrev = floor(n21(c + (tick - 1.0) * 0.618) * glyphCount);
}

// Smooth gradient noise with rotated octaves: no square lattice shows through (the old value noise
// read as moving rectangles).
float gnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float2 ga = float2(n21(i), n21(i + 19.19)) * 2.0 - 1.0;
    float2 gb = float2(n21(i + float2(1.0, 0.0)), n21(i + float2(1.0, 0.0) + 19.19)) * 2.0 - 1.0;
    float2 gc = float2(n21(i + float2(0.0, 1.0)), n21(i + float2(0.0, 1.0) + 19.19)) * 2.0 - 1.0;
    float2 gd = float2(n21(i + float2(1.0, 1.0)), n21(i + float2(1.0, 1.0) + 19.19)) * 2.0 - 1.0;
    float a = dot(ga, f);
    float b = dot(gb, f - float2(1.0, 0.0));
    float c = dot(gc, f - float2(0.0, 1.0));
    float d = dot(gd, f - float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 0.75 + 0.5;
}

float fbm(float2 p) {
    float s = 0.55 * gnoise(p);
    p = float2(1.6 * p.x + 1.2 * p.y, -1.2 * p.x + 1.6 * p.y) + float2(3.1, 1.7);
    s += 0.275 * gnoise(p);
    p = float2(1.6 * p.x + 1.2 * p.y, -1.2 * p.x + 1.6 * p.y) + float2(3.1, 1.7);
    s += 0.1375 * gnoise(p);
    return s / 0.9625;
}

// Smoothstep that also accepts falling edges (a > b).
float ss(float a, float b, float x) {
    float t = clamp((x - a) / (b - a), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float sdCapsule(float2 p, float2 a, float2 b, float r) {
    float2 pa = p - a;
    float2 ba = b - a;
    float k = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * k) - r;
}

float handSd(float2 p, float tip) {
    float d = sdCapsule(p, float2(-0.12, 0.11), float2(0.17, 0.045), 0.052);
    d = min(d, sdCapsule(p, float2(0.17, 0.045), float2(0.26, 0.035), 0.046));
    d = min(d, sdCapsule(p, float2(0.26, 0.025), float2(tip, 0.04), 0.013));
    d = min(d, sdCapsule(p, float2(0.26, 0.06), float2(0.32, 0.085), 0.014));
    d = min(d, sdCapsule(p, float2(0.25, 0.08), float2(0.3, 0.108), 0.013));
    return min(d, sdCapsule(p, float2(0.215, 0.0), float2(0.285, -0.014), 0.013));
}

float clockHand(float2 p, float ang, float len, float w) {
    float2 h = float2(sin(ang), -cos(ang));
    float k = clamp(dot(p, h), 0.0, len);
    return ss(w, w * 0.2, length(p - h * k));
}

// Cyclone Tide: each scene is a soft brightness map (0..1) sampled once per digit. Nothing moves
// faster than about one digit per second. uv is 0..1 across the screen; asp = height / width.
float scene(float id, float2 uv, float t, float asp) {
    float x = uv.x;
    float y = uv.y;
    float Y = y * asp;
    if (id < 0.5) {
        // Cyclone: the spiral mark, turning slowly.
        float2 q = float2(x - 0.5, (y - 0.5) * asp);
        float r = length(q) + 0.0001;
        float a = atan(q.y, q.x);
        float s = sin(3.0 * a - log(r + 0.02) * 3.2 + t * 0.35);
        return 0.04 + ss(0.2, 0.9, 0.5 + 0.5 * s) * ss(0.78, 0.08, r) * 0.95 + ss(0.12, 0.0, r) * 0.7;
    }
    if (id < 1.5) {
        // Tide: a slow swell with foam on the crests.
        float w = fbm(float2(x * 1.4 + t * 0.012, Y * 1.4 - t * 0.008));
        float crest = sin(Y * 5.5 + w * 3.2 - t * 0.42 + x * 1.2);
        float band = ss(0.35, 1.0, 0.5 + 0.5 * crest);
        float foam = ss(0.9, 1.0, 0.5 + 0.5 * crest);
        return 0.08 + 0.55 * band * (0.55 + 0.45 * fbm(float2(x * 3.0 + 7.0, Y * 3.0 - t * 0.02))) + 0.3 * foam;
    }
    if (id < 2.5) {
        // Contour: drifting topographic lines; every fourth line is an index line.
        float v = fbm(float2(x * 1.3 + t * 0.01, Y * 1.3 - t * 0.006 + 3.1)) * 7.0 + t * 0.03;
        float k = floor(v + 0.5);
        float line = ss(0.16, 0.02, abs(v - k));
        float index = mod(k, 4.0) < 0.5 ? 1.0 : 0.55;
        return 0.05 + 0.9 * line * index;
    }
    if (id < 3.5) {
        // Weave: columns of digits rippling like a surface in slow wind.
        float h = (fbm(float2(x * 1.1 + t * 0.006, Y * 0.9 - t * 0.015)) - 0.5) * 0.55 + 0.05 * sin(Y * 3.2 + t * 0.25);
        float v = (x + h) * 11.0;
        float line = ss(0.22, 0.03, abs(v - floor(v + 0.5)));
        return 0.05 + line * (0.25 + 0.75 * ss(0.25, 0.8, fbm(float2(x * 2.2 - 3.0, Y * 1.6 + t * 0.018))));
    }
    if (id < 4.5) {
        // First light: a beam from the top of the screen falling onto stairs.
        float cx = 0.5 + 0.015 * sin(t * 0.13);
        float breath = 0.85 + 0.15 * sin(t * 0.45);
        float floorY = 0.64;
        if (y < floorY) {
            float halfW = 0.05 + 0.17 * (y / floorY);
            float e = abs(x - cx);
            float beam = ss(halfW, halfW - 0.05, e) * (0.45 + 0.55 * (1.0 - y / floorY));
            float side = x < cx ? -1.0 : 1.0;
            float wall = e > halfW ? 0.09 * ss(0.3, 1.0, 0.5 + 0.5 * sin((x + side * y * 0.06) * 95.0)) : 0.0;
            return (beam + wall) * breath;
        }
        float st = y - floorY;
        float ph = log(1.0 + st * 22.0) * 7.0;
        float stripe = ss(0.28, 0.06, abs(ph - floor(ph + 0.5)));
        float spread = ss(1.0, 0.1, abs(x - cx) / (0.24 + 0.9 * st));
        return 0.04 + stripe * spread * (1.0 - (st / 0.36) * 0.75) * breath;
    }
    if (id < 5.5) {
        // Clock: a face with ticks; the minute hand turns once a minute and leaves a soft trail.
        float R = 0.31;
        float2 q = float2(x - 0.5, Y - asp * 0.46);
        float r = length(q);
        if (r > R + 0.06) return 0.04;
        float a = atan(q.x, -q.y);
        float ring = ss(0.03, 0.004, abs(r - R));
        float major = ss(0.9, 0.98, cos(4.0 * a));
        float minor = ss(0.88, 0.97, cos(12.0 * a));
        float ticks = max(major * ss(R * 0.74, R * 0.8, r), minor * 0.7 * ss(R * 0.84, R * 0.88, r)) * ss(R * 0.95, R * 0.9, r);
        float minuteA = t * 6.28318 / 60.0;
        float hourA = t * 6.28318 / 720.0;
        float behind = mod(minuteA - a, 6.28318);
        float trail = (behind < 1.2 && r < R * 0.9) ? (1.0 - behind / 1.2) * (1.0 - behind / 1.2) * 0.35 : 0.0;
        float d = max(ring, ticks);
        d = max(d, clockHand(q, minuteA, R * 0.82, 0.03));
        d = max(d, clockHand(q, hourA, R * 0.52, 0.045) * 0.9);
        d = max(d, max(trail, ss(0.05, 0.0, r)));
        return 0.04 + d;
    }
    // Reach: two hands drift toward each other; a spark waits between the fingertips.
    float Y0 = asp * 0.47;
    float s = 0.07 + 0.015 * sin(t * 0.25);
    float tip = 0.39 + s;
    float dl = handSd(float2(x, (Y - Y0) / 2.6), tip);
    float dr = handSd(float2(1.0 - x, (Y - (Y0 - 0.14)) / 2.6), tip);
    float hands = max(ss(0.02, -0.008, dl), ss(0.02, -0.008, dr)) * (0.6 + 0.4 * fbm(float2(x * 9.0, Y * 9.0)));
    float gap = 1.0 - 2.0 * tip;
    float2 m = float2(x - 0.5, Y - Y0 - 0.02);
    float spark = exp(-dot(m, m) / 0.0016) * ss(0.2, 0.06, gap) * (0.6 + 0.4 * sin(t * 1.1));
    return 0.035 + max(hands * 0.95, spark);
}

float3 iridescent(float2 xy) {
    float hue = fract(xy.y / res.y * 0.8 + xy.x / res.x * 0.3 + clock * 0.15);
    return 0.55 + 0.45 * cos(6.28318 * (hue + float3(0.0, 0.33, 0.67)));
}

half4 main(float2 xy) {
    // Cyclone's own chrome (Ask bar, work panel) sits over a soft cut-out: digits never show through it.
    float2 exclHalf = (excl.zw - excl.xy) * 0.5;
    float dExcl = sdRoundRect(xy - (excl.xy + exclHalf), exclHalf, exclRadius);
    if (dExcl < 0.0) return half4(0.0);
    float chromeFade = smoothstep(0.0, exclFeather, dExcl);

    float2 q = xy;
    float fade = 1.0;
    if (rain >= 0.0) {
        float col = floor(xy.x / cell.x);
        float delay = h21(float2(col, 91.0)) * 0.35;
        float k = clamp((rain - delay) / 0.65, 0.0, 1.0);
        q = float2(xy.x, xy.y - k * k * res.y * 0.9);
        fade = 1.0 - smoothstep(0.55, 1.0, rain);
    }

    // Edge filament zone (cheap): one column per side.
    float de = min(min(xy.x, res.x - xy.x), min(xy.y, res.y - xy.y));
    bool edgeZone = edge > 0.0 && de < cell.x * 1.25;

    // Glyphs first: most pixels are not inside a digit, and they leave here before any field maths.
    float2 local; float g; float gPrev; float age;
    float core = 0.0;
    float halo = 0.0;
    float ghost = 0.0;
    float coreR = 0.0;
    float coreB = 0.0;
    float2 cellId; float2 cellCentre;
    cellAt(q, cell, 0.0, flow, local, g, gPrev, age, cellId, cellCentre);
    // Cross-fade from the previous digit over the first 40% of each tick: no hard cuts.
    float swap = smoothstep(0.0, 0.4, age);
    core = atlasA(g, local, 0.0);
    halo = atlasA(g, local, 1.0);
    if (swap < 1.0) {
        core = mix(atlasA(gPrev, local, 0.0), core, swap);
        halo = mix(atlasA(gPrev, local, 1.0), halo, swap);
    }
    // One grid only: a second, offset layer would sit between these digits and break the even field.
    float far = 0.0;
    if (style > 0.5 && style < 1.5) {
        // Forge: the previous digit lingers as a cooling afterglow.
        ghost = atlasA(gPrev, local, 0.0) * 0.4 * (1.0 - smoothstep(0.0, 0.3, age));
    }
    if (core + halo + far + ghost < 0.002) return half4(0.0);

    // Sharp attention: the rounded-rect lens that morphs onto the target.
    float d = sdRoundRect(q - lens.xy, lens.zw, lensCorner);
    float lensCoreMask = 1.0 - smoothstep(0.0, lensSoft, d);
    lensCoreMask *= lensCoreMask;
    // Two broad gradients so the field never reads as a cut-out: a natural oval around the lens
    // and a soft vertical band that reaches about half the screen height.
    float2 rel = (q - lens.xy) / (lens.zw + float2(res.x * 0.3, res.y * 0.16));
    float oval = exp(-2.2 * dot(rel, rel));
    float vy = (q.y - lens.y) / (res.y * 0.3);
    float band = exp(-1.6 * vy * vy);
    float focused = max(lensCoreMask, max(oval * 0.38, band * 0.13));
    // The lens only takes over when Cyclone looks at something; while it thinks, the scene carries.
    float lensM = focused * smoothstep(0.3, 0.9, focus);

    if (ripple.w > 0.0) {
        float ring = abs(length(xy - ripple.xy) - ripple.z);
        lensM = max(lensM, (1.0 - smoothstep(0.0, cell.y * 1.3, ring)) * ripple.w);
    }
    if (scan.y > 0.0) {
        float scanBand = 1.0 - smoothstep(0.0, cell.y * 2.2, abs(xy.y - scan.x));
        lensM = max(lensM, scanBand * scan.y);
    }
    float m = lensM * intensity * fade;

    // Cyclone Tide scene layer, sampled at the digit's centre so each digit is lit evenly. A digit
    // is fully lit when the scene beats its own fixed threshold (halftone), else it glows faintly.
    if (sceneLevel > 0.001) {
        float asp = res.y / res.x;
        float2 uv = cellCentre / res;
        float dens = scene(sceneA, uv, flowTime, asp);
        float foam = 0.0;
        if (sceneFront < 1.5) {
            float pos = sceneRadial > 0.5 ? length((uv - 0.5) * float2(1.0, asp)) / 1.1 : 1.0 - uv.y;
            float tideLine = pos + (gnoise(float2(uv.x * 3.2, flowTime * 0.2)) - 0.5) * 0.14;
            float into = 1.0 - smoothstep(sceneFront - 0.09, sceneFront + 0.09, tideLine);
            dens = mix(dens, scene(sceneB, uv, flowTime, asp), into);
            float dt = tideLine - sceneFront;
            foam = exp(-dt * dt / 0.0009) * 0.55;
        }
        dens = min(1.0, dens + foam);
        float threshold = 0.05 + 0.7 * n21(cellId + 17.3);
        float lit = dens > threshold ? 0.1 + 0.34 * dens : 0.03 + 0.03 * dens;
        float swell = 0.82 + 0.18 * sin(flowTime * 0.45 + uv.y * 4.0);
        m = max(m, lit * swell * sceneLevel * (1.0 - 0.55 * focus) * fade);
    }
    m *= chromeFade;

    float e = 0.0;
    if (edgeZone) {
        float perimeter = 2.0 * (res.x + res.y);
        float s = 0.0;
        if (de == xy.y) { s = xy.x; }
        else if (de == res.x - xy.x) { s = res.x + xy.y; }
        else if (de == res.y - xy.y) { s = res.x + res.y + (res.x - xy.x); }
        else { s = 2.0 * res.x + res.y + (res.y - xy.y); }
        float behind = mod(edgeHead * perimeter - s + perimeter, perimeter);
        float tail = res.y * 0.4;
        if (behind < tail) {
            e = (1.0 - behind / tail) * (1.0 - smoothstep(cell.x * 0.35, cell.x * 1.25, de)) * edge * 0.85;
        }
    }

    float mask = max(m, e);
    if (mask < 0.004) return half4(0.0);

    // Slow continuous shimmer per cell (was a random brightness step twice a second).
    float twinkle = 0.72 + 0.28 * sin(clock * 1.1 + n21(floor(q / cell)) * 6.28318);
    coreR = core;
    coreB = core;
    if (style < 0.5) {
        // Obsidian: a 1.5 px red/blue split only on the lens rim, like real glass.
        float rim = focus * smoothstep(-lensSoft * 0.1, lensSoft * 0.15, d) * (1.0 - smoothstep(lensSoft * 0.15, lensSoft * 0.6, d));
        if (rim > 0.01) {
            coreR = mix(core, atlasA(g, local + float2(1.5, 0.0), 0.0), rim);
            coreB = mix(core, atlasA(g, local - float2(1.5, 0.0), 0.0), rim);
        }
    }
    float light = 0.0;
    float mid = 0.0;
    if (backdropOn > 0.5) {
        half4 bg = backdrop.eval(xy / res * gridSize);
        float lum = dot(float3(bg.rgb), float3(0.2126, 0.7152, 0.0722));
        // Only clearly light content flips to ink; colourful mid-tones keep glow plus a dark halo,
        // which stays crisp over photos where a half-way colour would turn muddy.
        light = smoothstep(0.6, 0.7, lum);
        mid = smoothstep(0.2, 0.45, lum) * (1.0 - light);
    }

    float lensCore = (0.35 + 0.65 * focus) * (1.0 - smoothstep(0.0, lensSoft * 0.9, max(d, 0.0) + lensSoft * 0.25));
    // Over mid-tone content (photos, video) the core runs whiter so it never greys into the image.
    float3 glow = float3(mix(tint.rgb, hot.rgb, half(max(lensCore * 0.7, mid * 0.8))));
    float3 inkRgb = float3(ink.rgb);
    float3 coreCol = glow;
    float3 haloCol = float3(0.02, 0.03, 0.06);
    float haloStrength = 0.5;

    if (style < 0.5) {
        // Obsidian: glowing digits on dark content, ink digits on light content (a live Difference).
        coreCol = mix(glow, inkRgb, light);
        haloCol = mix(float3(0.02, 0.03, 0.06), float3(0.97, 0.98, 1.0), light);
        haloStrength = 0.7;
    } else if (style < 1.5) {
        // Forge: born white-hot, cools to Cyclone blue, dies as an ember.
        float3 whiteHot = float3(1.0, 0.97, 0.9);
        float3 blue = float3(tint.rgb);
        float3 ember = float3(1.0, 0.42, 0.16);
        float3 heat = mix(whiteHot, blue, smoothstep(0.0, 0.4, age));
        heat = mix(heat, ember, smoothstep(0.55, 0.95, age));
        coreCol = mix(heat, heat * 0.55, light);
        core *= 1.0 - 0.55 * smoothstep(0.5, 1.0, age);
        haloStrength = 0.4;
    } else if (style < 2.5) {
        // Chameleon: takes on the colour of the app it is working in.
        float3 acc = float3(accent.rgb);
        coreCol = mix(mix(acc, float3(1.0), lensCore * 0.45), acc * 0.4, light);
        haloCol = mix(float3(0.02, 0.03, 0.06), float3(0.97, 0.98, 1.0), light);
        haloStrength = 0.65;
    } else {
        // Signal: print-style halftone dots toward the lens edge; iridescent finale.
        float dist01 = clamp(d / lensSoft + 0.35, 0.0, 1.0);
        float radius = 0.5 * (1.0 - dist01) + 0.12;
        float f = length(fract(xy / 3.0) - 0.5);
        float dots = 1.0 - smoothstep(radius - 0.1, radius, f);
        float amount = smoothstep(0.1, 0.45, dist01);
        core = mix(core, core * dots, amount);
        coreR = core;
        coreB = core;
        far *= mix(1.0, dots, amount);
        coreCol = mix(glow, inkRgb, light);
        if (rain >= 0.0) coreCol = mix(coreCol, iridescent(xy), 0.85);
        haloStrength = 0.65 * (1.0 - amount);
    }
    coreCol = mix(coreCol, float3(warm.rgb), warmth);

    float a = mask * opacity;
    float cA = max(core * twinkle, far) * a;
    float rA = max(coreR * twinkle, far) * a;
    float bA = max(coreB * twinkle, far) * a;
    float gA = ghost * a;
    float hA = halo * haloStrength * a * (1.0 - cA);
    float alpha = clamp(max(max(rA, bA), cA) + gA * (1.0 - cA) + hA, 0.0, 1.0);
    if (alpha < 0.002) return half4(0.0);
    float3 ember = float3(1.0, 0.42, 0.16);
    float3 rgb = float3(coreCol.r * rA, coreCol.g * cA, coreCol.b * bA) + ember * gA * (1.0 - cA) + haloCol * hA;
    return half4(half3(rgb), half(alpha));
}
""".trimIndent()
}
