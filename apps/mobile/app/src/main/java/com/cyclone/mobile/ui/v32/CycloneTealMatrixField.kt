package com.cyclone.mobile.ui.v32

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

/**
 * The living Teal Matrix canvas and the teal glass that sits on it.
 *
 * Canvas: one AGSL pass behind every in-app screen: teal base, two slow aurora ribbons (40-60 s
 * drifts), breathing blooms, and a fixed dot matrix that only brightens where a ribbon passes.
 *
 * Glass (controls layer only): each glass surface evaluates the same scene at its own screen
 * position, frosted and without the dots, then adds thick-glass edge lensing, vibrancy and a
 * specular rim. There is no layer capture and no blur pass, so the cost is one small shader per
 * surface instead of re-blurring the whole moving screen for every control every frame. Scene and
 * glass share one clock, so they move together.
 *
 * Budget: 24 fps cap (the motion is 40-60 s slow), static dither (no per-frame grain), frozen when
 * "Remove animations" is on, and both shaders compile on a background thread at app start so the
 * first screen never waits for them.
 */
internal object TealMatrixField {
    const val FPS_CAP = 24f
    /** Start mid-cycle so the first frame already has a ribbon in view. */
    const val START_S = 30f

    private const val COMMON = """
float h(float2 p) {
    p = fract(p * float2(233.34, 851.73));
    p += dot(p, p + 23.45);
    return fract(p.x * p.y);
}
float vn(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(h(i), h(i + float2(1.0, 0.0)), f.x), mix(h(i + float2(0.0, 1.0)), h(i + float2(1.0, 1.0)), f.x), f.y);
}
float ribbon(float2 uv, float s, float phase) {
    float2 w = float2(vn(uv * 1.1 + float2(s * 0.020, -s * 0.013) + phase), vn(uv * 1.1 + float2(-s * 0.017, s * 0.021) + phase + 5.2));
    float n = vn(uv * float2(1.4, 2.4) + w * 1.6 + float2(0.0, s * 0.03));
    float band = 0.5 + 0.5 * sin((uv.x * 1.3 - uv.y * 2.2) * 2.4 + n * 3.2 + s * 0.09 + phase);
    return smoothstep(0.45, 0.95, band) * smoothstep(0.30, 0.75, n);
}
float glowAt(float2 uv, float t) {
    return ribbon(uv, t, 0.0) * 0.9 + ribbon(uv * 0.8 + 3.7, t * 0.7, 2.1) * 0.55;
}
// The scene without dots or grain: exactly what frosted glass should see.
float3 sceneBody(float2 xy, float2 res, float t, float glow) {
    float2 uv = xy / res.y;
    float y = xy.y / res.y;
    float3 top = float3(0.016, 0.082, 0.098);
    float3 mid = float3(0.027, 0.150, 0.172);
    float3 low = float3(0.018, 0.098, 0.118);
    float3 col = mix(mix(top, mid, smoothstep(0.0, 0.45, y)), low, smoothstep(0.45, 1.0, y));
    col += float3(0.10, 0.36, 0.34) * glow * 0.32;
    float breathe = 0.5 + 0.5 * sin(t * 0.157);
    float2 c = uv - float2(res.x / res.y * 0.85, 0.05);
    col += float3(0.12, 0.42, 0.40) * 0.18 * exp(-3.0 * dot(c, c)) * (0.7 + 0.3 * breathe);
    float2 v = xy / res - 0.5;
    col *= 1.0 - 0.28 * dot(v, v);
    return col;
}
"""

    val SOURCE = """
uniform float2 res;
uniform float t;
uniform float density;
half4 main(float2 xy) {
    float glow = glowAt(xy / res.y, t);
    float3 col = sceneBody(xy, res, t, glow);
    float cellPx = 11.0 * density;
    float2 cell = floor(xy / cellPx);
    float2 f = xy - (cell + 0.5) * cellPx;
    float lit = clamp(glow * 1.2, 0.0, 1.0) * (0.55 + 0.45 * vn(cell * 0.35 + t * 0.05));
    float r = (0.35 + 1.05 * lit) * density;
    float dotMask = 1.0 - smoothstep(r - 0.6 * density, r + 0.6 * density, length(f));
    col += float3(0.51, 0.86, 0.84) * dotMask * (0.05 + 0.34 * lit);
    // Static 1/255 dither (no per-frame grain).
    col += (h(xy) - 0.5) / 255.0;
    return half4(half3(col), 1.0);
}
""".replace("uniform float density;\n", "uniform float density;\n" + COMMON)

    val GLASS_SOURCE = """
uniform float2 screen;
uniform float2 origin;
uniform float2 size;
uniform float radius;
uniform float t;
uniform float density;
uniform float4 tint;
uniform float press;
float sdRR(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
float3 frosted(float2 p) {
    // Three taps of the smooth scene read as a soft frost; no dots, so no refraction sparkle.
    float s = 7.0 * density;
    float2 uvScale = float2(1.0 / screen.y);
    float3 a = sceneBody(p, screen, t, glowAt(p * uvScale, t));
    float3 b = sceneBody(p + float2(s, -s * 0.6), screen, t, glowAt((p + float2(s, -s * 0.6)) * uvScale, t));
    float3 c = sceneBody(p + float2(-s, s * 0.6), screen, t, glowAt((p + float2(-s, s * 0.6)) * uvScale, t));
    return a * 0.5 + (b + c) * 0.25;
}
half4 main(float2 xy) {
    float2 hs = size * 0.5;
    float2 q = xy - hs;
    float r = min(radius, min(hs.x, hs.y));
    float d = sdRR(q, hs, r);
    if (d > 1.0) return half4(0.0);
    float cover = clamp(0.5 - d, 0.0, 1.0);
    // Surface normal from the SDF gradient.
    float e = 1.0;
    float2 n = normalize(float2(sdRR(q + float2(e, 0.0), hs, r) - sdRR(q - float2(e, 0.0), hs, r),
                                sdRR(q + float2(0.0, e), hs, r) - sdRR(q - float2(0.0, e), hs, r)) + 1e-5);
    // Thick-glass lensing: the scene is pulled inward inside an edge band, magnifying the rim.
    float band = min(16.0 * density, r * 0.9 + 1.0);
    float edge = clamp(1.0 + d / band, 0.0, 1.0);
    float bend = edge * edge * edge;
    float2 p = origin + xy - n * bend * band * 1.1;
    float3 c = frosted(p);
    // Vibrancy: lift and saturate what shows through, then the teal body.
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    c = mix(float3(l), c, 1.45) * 1.55 + 0.018;
    c = mix(c, tint.rgb, tint.a);
    // Soft top sheen and a darker foot give the slab volume.
    float yN = q.y / hs.y;
    c += float3(0.05, 0.09, 0.09) * smoothstep(0.2, -1.0, yN);
    c *= 1.0 - 0.10 * smoothstep(0.1, 1.0, yN);
    // Specular rim: key light from the top left, faint teal counter light below.
    float rim = smoothstep(-1.8 * density, -0.2 * density, d) * (1.0 - smoothstep(-0.2 * density, 0.6 * density, d));
    float key = max(dot(n, normalize(float2(-0.45, -0.89))), 0.0);
    float fill = max(dot(n, normalize(float2(0.45, 0.89))), 0.0);
    c += rim * (0.10 + 0.62 * key * key) * float3(0.92, 1.0, 1.0);
    c += rim * 0.30 * fill * float3(0.35, 0.86, 0.82);
    // Inner glow just inside the rim, strongest on the lit side.
    float inner = smoothstep(-7.0 * density, -1.5 * density, d) * (1.0 - smoothstep(-1.5 * density, 0.0, d));
    c += inner * 0.07 * (0.4 + key);
    // Pressed glass brightens and blooms from within.
    c += press * (0.06 + 0.05 * (1.0 - edge)) * float3(0.55, 0.95, 0.92);
    return half4(half3(c) * half(cover), half(cover));
}
""".replace("uniform float press;\n", "uniform float press;\n" + COMMON)

    /** Advances the clock only when a full frame interval has elapsed (10 % tolerance for vsync jitter); dt is clamped so a resume never jumps. */
    fun advance(clock: Float, lastFrameNanos: Long, nowNanos: Long): Pair<Float, Long> {
        if (lastFrameNanos == 0L) return clock to nowNanos
        val dt = (nowNanos - lastFrameNanos) / 1_000_000_000f
        if (dt < 0.9f / FPS_CAP) return clock to lastFrameNanos
        return clock + dt.coerceAtMost(0.1f) to nowNanos
    }

    fun motionReduced(animatorScale: Float): Boolean = animatorScale == 0f
}

/** Shared scene clock and size; the canvas drives it, glass surfaces read it. */
internal object TealMatrixClock {
    var t by mutableFloatStateOf(TealMatrixField.START_S)
    var width by mutableFloatStateOf(0f)
    var height by mutableFloatStateOf(0f)
}

/**
 * Compiled once, off the main thread. AGSL compilation used to happen on the first frame, which
 * is the kind of work that makes an app stall when it opens.
 */
internal object TealMatrixShaders {
    @Volatile private var field: RuntimeShader? = null
    @Volatile private var glass: RuntimeShader? = null
    @Volatile private var failed = false

    fun warmAsync() {
        Thread({ runCatching { field(); glass() } }, "teal-matrix-shaders").apply { priority = Thread.MIN_PRIORITY + 2 }.start()
    }

    @Synchronized fun field(): RuntimeShader? {
        if (field == null && !failed) field = runCatching { RuntimeShader(TealMatrixField.SOURCE) }.onFailure { failed = true }.getOrNull()
        return field
    }

    @Synchronized fun glass(): RuntimeShader? {
        if (glass == null && !failed) glass = runCatching { RuntimeShader(TealMatrixField.GLASS_SOURCE) }.onFailure { failed = true }.getOrNull()
        return glass
    }
}

/** True under an in-app screen that draws the living canvas; the floating overlay never does. */
internal val LocalTealMatrixField = compositionLocalOf { false }

@Composable
internal fun TealMatrixBackdrop(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val shader = remember { TealMatrixShaders.field() }
    if (shader == null) {
        TealMatrixStaticBackdrop(modifier)
        return
    }
    val reduced = remember {
        TealMatrixField.motionReduced(
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f),
        )
    }
    if (!reduced) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    val (next, stamp) = TealMatrixField.advance(TealMatrixClock.t, last, now)
                    last = stamp
                    if (next != TealMatrixClock.t) TealMatrixClock.t = next
                }
            }
        }
    }
    val paint = remember { Paint().apply { this.shader = shader } }
    Box(
        modifier.drawBehind {
            if (TealMatrixClock.width != size.width) TealMatrixClock.width = size.width
            if (TealMatrixClock.height != size.height) TealMatrixClock.height = size.height
            shader.setFloatUniform("res", size.width, size.height)
            shader.setFloatUniform("t", TealMatrixClock.t)
            shader.setFloatUniform("density", density)
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        },
    )
}

/**
 * Teal glass optics for a surface on the living canvas. [press] 0..1 brightens it from within.
 * Falls back to nothing (the caller's painted glass shows) when no canvas is present.
 */
internal fun Modifier.tealGlass(
    cornerRadius: Dp,
    tintRgb: FloatArray = floatArrayOf(0.043f, 0.20f, 0.22f),
    tintAmount: Float = 0.34f,
    press: () -> Float = { 0f },
): Modifier = composed {
    val shader = remember { TealMatrixShaders.glass() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val paint = remember { Paint() }
    val density = LocalDensity.current.density
    if (shader == null) return@composed this
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .drawBehind {
            val w = TealMatrixClock.width
            val h = TealMatrixClock.height
            if (w <= 0f || h <= 0f) return@drawBehind
            shader.setFloatUniform("screen", w, h)
            shader.setFloatUniform("origin", origin.x, origin.y)
            shader.setFloatUniform("size", size.width, size.height)
            shader.setFloatUniform("radius", cornerRadius.toPx())
            shader.setFloatUniform("t", TealMatrixClock.t)
            shader.setFloatUniform("density", density)
            shader.setFloatUniform("tint", tintRgb[0], tintRgb[1], tintRgb[2], tintAmount)
            shader.setFloatUniform("press", press())
            paint.shader = shader
            drawIntoCanvas { it.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint) }
        }
}
