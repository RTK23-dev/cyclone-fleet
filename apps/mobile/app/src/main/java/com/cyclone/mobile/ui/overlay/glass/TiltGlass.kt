package com.cyclone.mobile.ui.overlay.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Where the light comes from, shared by every glass surface. Reads happen in the draw phase, so a new light only
 * redraws the glass; nothing recomposes.
 */
object GlassLight {
    var x by mutableFloatStateOf(GlassOptics.REST_X)
        private set
    var y by mutableFloatStateOf(GlassOptics.REST_Y)
        private set

    internal fun ease(tx: Float, ty: Float, amount: Float = 0.22f) {
        x += (tx - x) * amount
        y += (ty - y) * amount
    }

    internal fun rest() {
        x = GlassOptics.REST_X
        y = GlassOptics.REST_Y
    }
}

/**
 * While Cyclone's glass is on screen, the light follows how the owner tilts the phone (gravity sensor). The grip they
 * start with is the baseline, and it slowly re-centres, so the light answers movement, not posture. With animations
 * switched off in Android settings the light stays still.
 */
@Composable
fun FollowPhoneLight() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val still = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (still || sensors == null || sensor == null) {
            GlassLight.rest()
            return@DisposableEffect onDispose { }
        }
        var baseX = Float.NaN
        var baseY = Float.NaN
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val gx = event.values[0]
                val gy = event.values[1]
                if (baseX.isNaN()) { baseX = gx; baseY = gy }
                baseX += (gx - baseX) * 0.004f
                baseY += (gy - baseY) * 0.004f
                val (tx, ty) = GlassOptics.lightFromGravity(gx, gy, baseX, baseY)
                GlassLight.ease(tx, ty)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
}

private val Teal = Color(0xFF83DBD7)

/**
 * The glass's palette. [TEAL] is Tilt Glass as the overlay has it. [SMOKE] is the AI screen's neutral glass (R3): the
 * same optics in graphite and white, with silver dots, so the cards sitting on the rain carry no teal.
 */
enum class GlassPalette(
    internal val body: Triple<Color, Color, Color>,
    internal val sheen: Color,
    internal val dotRgb: Int,
    internal val hairline: Color,
    internal val glow: Color,
    internal val rim: Color,
) {
    TEAL(Triple(Color(0xFF10383E), Color(0xFF062228), Color(0xFF08282E)), Teal, 0x83DBD7, Color(0xFFA0E2DE), Color(0xFF83E6DE), Color(0xFFF0FFFD)),
    SMOKE(Triple(Color(0xFF1C1E23), Color(0xFF0F1013), Color(0xFF14161A)), Color(0xFFFFFFFF), 0xD6DCE4, Color(0xFFFFFFFF), Color(0xFFE8ECF2), Color(0xFFFFFFFF)),
}

/** The palette glass under this point uses; surfaces that call [tiltGlass] pass it on. The overlay never sets it. */
val LocalGlassPalette = androidx.compose.runtime.staticCompositionLocalOf { GlassPalette.TEAL }

/**
 * The glass itself (plan 27): dark teal body with a sheen leaning towards the light, a crisp hairline, a sharp shine
 * on the rim sections facing the light (and a weaker one opposite), and optional halftone fingerprint dots along the
 * edge. [thin] is the fine line for small pills; [seeThrough] below 1 lets the app show through (the idle bubble).
 */
fun Modifier.tiltGlass(
    cornerRadius: Dp,
    dots: Boolean = true,
    thin: Boolean = false,
    seeThrough: Float = 1f,
    palette: GlassPalette = GlassPalette.TEAL,
): Modifier = drawWithCache {
    val w = size.width
    val h = size.height
    val r = min(cornerRadius.toPx(), min(w, h) / 2f)
    val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r))) }
    val body = Brush.verticalGradient(
        0f to palette.body.first.copy(alpha = 0.94f * seeThrough),
        0.55f to palette.body.second.copy(alpha = 0.95f * seeThrough),
        1f to palette.body.third.copy(alpha = 0.95f * seeThrough),
    )
    // Halftone band: dot centres and their place relative to the rim, computed once per size.
    val step = 4.3.dp.toPx()
    val band = min(30.dp.toPx(), h * 0.46f)
    val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val falls = ArrayList<Float>()
    if (dots && w > 0f && h > 0f) {
        var row = 0
        var y = step / 2f
        while (y < h) {
            var x = step / 2f + if (row % 2 == 1) step / 2f else 0f
            while (x < w) {
                val inside = -GlassOptics.roundedRectDistance(x, y, w, h, r)
                if (inside in 0f..band) { xs += x; ys += y; falls += 1f - inside / band }
                x += step
            }
            y += step
            row++
        }
    }
    val dotCount = xs.size
    val nxs = FloatArray(dotCount) { (xs[it] - w / 2f) / (w / 2f) }
    val nys = FloatArray(dotCount) { (ys[it] - h / 2f) / (h / 2f) }
    val buckets = Array(ALPHA_LEVELS * RADIUS_LEVELS) { FloatArray(0) }
    val counts = IntArray(ALPHA_LEVELS * RADIUS_LEVELS)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val dpPx = 1.dp.toPx()
    val rim = rimSegments(w - 1.2f, h - 1.2f, max(0f, r - 0.6f), 260)
    val hairline = Stroke(0.8.dp.toPx())
    val glowWidth = 3.2.dp.toPx()
    val lineWidth = (if (thin) 0.6.dp else 1.15.dp).toPx()

    onDrawBehind {
        val lx = GlassLight.x
        val ly = GlassLight.y
        val theta = GlassOptics.angle(lx, ly)
        val power = GlassOptics.power(lx, ly)
        clipPath(outline) {
            drawRect(body)
            val span = max(w, h) * 0.6f
            drawRect(Brush.linearGradient(
                0f to palette.sheen.copy(alpha = 0.16f * power * seeThrough), 0.45f to Color.Transparent,
                0.8f to Color.Transparent, 1f to palette.sheen.copy(alpha = 0.06f * power * seeThrough),
                start = Offset(w / 2f + lx * span, h / 2f + ly * span), end = Offset(w / 2f - lx * span, h / 2f - ly * span),
            ))
            if (dotCount > 0) {
                counts.fill(0)
                for (i in 0 until dotCount) {
                    val alpha = GlassOptics.dotAlpha(nxs[i], nys[i], falls[i], lx, ly, power)
                    if (alpha < 0.015f) continue
                    val a = (alpha * (ALPHA_LEVELS - 1) / 0.85f).roundToInt().coerceIn(0, ALPHA_LEVELS - 1)
                    val radius = GlassOptics.dotRadius(nxs[i], nys[i], falls[i], lx, ly)
                    val rq = ((radius - 0.2f) / 2.4f * (RADIUS_LEVELS - 1)).roundToInt().coerceIn(0, RADIUS_LEVELS - 1)
                    val b = a * RADIUS_LEVELS + rq
                    if (buckets[b].size < dotCount * 2) buckets[b] = FloatArray(dotCount * 2)
                    buckets[b][counts[b] * 2] = xs[i]
                    buckets[b][counts[b] * 2 + 1] = ys[i]
                    counts[b]++
                }
                drawIntoCanvas { canvas ->
                    for (b in buckets.indices) {
                        if (counts[b] == 0) continue
                        val alpha = (b / RADIUS_LEVELS) * 0.85f / (ALPHA_LEVELS - 1)
                        val radius = 0.2f + (b % RADIUS_LEVELS) * 2.4f / (RADIUS_LEVELS - 1)
                        paint.color = android.graphics.Color.argb((alpha * 255).roundToInt(), (palette.dotRgb shr 16) and 0xFF, (palette.dotRgb shr 8) and 0xFF, palette.dotRgb and 0xFF)
                        paint.strokeWidth = radius * 2f * dpPx
                        canvas.nativeCanvas.drawPoints(buckets[b], 0, counts[b] * 2, paint)
                    }
                }
            }
        }
        drawPath(outline, palette.hairline.copy(alpha = 0.16f), style = hairline)
        for (pass in 0..1) {
            if (pass == 0 && thin) continue
            var i = 0
            while (i < rim.size) {
                val intensity = GlassOptics.rimIntensity(rim[i + 4], theta, thin)
                if (intensity >= 0.02f) {
                    val start = Offset(rim[i] + 0.6f, rim[i + 1] + 0.6f)
                    val end = Offset(rim[i + 2] + 0.6f, rim[i + 3] + 0.6f)
                    if (pass == 0) drawLine(palette.glow.copy(alpha = (0.3f * intensity * power).coerceIn(0f, 1f)),
                        start, end, glowWidth, StrokeCap.Round)
                    else drawLine(palette.rim.copy(alpha = min(if (thin) 0.8f else 1f, (if (thin) 0.8f else 1.15f) * intensity * power)),
                        start, end, lineWidth, StrokeCap.Round)
                }
                i += 5
            }
        }
    }
}

private const val ALPHA_LEVELS = 16
private const val RADIUS_LEVELS = 8

/** Segments around a rounded rectangle: x0, y0, x1, y1, outward normal angle — five floats each. */
internal fun rimSegments(w: Float, h: Float, radius: Float, n: Int): FloatArray {
    if (w <= 0f || h <= 0f) return FloatArray(0)
    val r = min(radius, min(w, h) / 2f)
    val sx = w - 2 * r
    val sy = h - 2 * r
    val arc = (PI * r / 2).toFloat()
    val per = 2 * sx + 2 * sy + 4 * arc
    fun point(u: Float): FloatArray {
        var d = u * per
        if (d < sx) return floatArrayOf(r + d, 0f, (-PI / 2).toFloat()); d -= sx
        if (d < arc) { val a = (-PI / 2).toFloat() + d / max(r, 0.001f); return floatArrayOf(w - r + cos(a) * r, r + sin(a) * r, a) }; d -= arc
        if (d < sy) return floatArrayOf(w, r + d, 0f); d -= sy
        if (d < arc) { val a = d / max(r, 0.001f); return floatArrayOf(w - r + cos(a) * r, h - r + sin(a) * r, a) }; d -= arc
        if (d < sx) return floatArrayOf(w - r - d, h, (PI / 2).toFloat()); d -= sx
        if (d < arc) { val a = (PI / 2).toFloat() + d / max(r, 0.001f); return floatArrayOf(r + cos(a) * r, h - r + sin(a) * r, a) }; d -= arc
        if (d < sy) return floatArrayOf(0f, h - r - d, PI.toFloat()); d -= sy
        val a = PI.toFloat() + d / max(r, 0.001f)
        return floatArrayOf(r + cos(a) * r, r + sin(a) * r, a)
    }
    val out = FloatArray(n * 5)
    for (i in 0 until n) {
        val p0 = point(i.toFloat() / n)
        val p1 = point(((i + 1) % n).toFloat() / n)
        out[i * 5] = p0[0]; out[i * 5 + 1] = p0[1]; out[i * 5 + 2] = p1[0]; out[i * 5 + 3] = p1[1]; out[i * 5 + 4] = p0[2]
    }
    return out
}
