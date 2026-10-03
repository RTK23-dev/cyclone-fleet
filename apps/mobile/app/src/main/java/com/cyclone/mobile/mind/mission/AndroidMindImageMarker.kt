package com.cyclone.mobile.mind.mission

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import com.cyclone.mobile.mind.MindImage
import com.cyclone.mobile.mind.MindImageMarker
import com.cyclone.mobile.mind.MindMark
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws each ref's box and name on the screenshot the Mind receives ("set of marks"), then scales it to at most
 * [MAX_DIMENSION] pixels and encodes it as JPEG. The model then points at "e7" instead of guessing pixels, and a phone
 * screenshot costs a fraction of the tokens of a full-resolution PNG. Runs in memory only; nothing is written to disk.
 */
internal object AndroidMindImageMarker : MindImageMarker {
    private const val MAX_DIMENSION = 1280
    private val palette = intArrayOf(
        Color.rgb(230, 25, 75), Color.rgb(0, 130, 200), Color.rgb(60, 160, 60), Color.rgb(245, 130, 48),
        Color.rgb(145, 30, 180), Color.rgb(0, 150, 150), Color.rgb(200, 30, 140), Color.rgb(120, 90, 40),
    )

    override fun mark(pngBase64: String, marks: List<MindMark>, screenWidth: Int, screenHeight: Int): MindImage? {
        val bytes = runCatching { Base64.decode(pngBase64, Base64.DEFAULT) }.getOrNull() ?: return null
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = min(1.0, MAX_DIMENSION.toDouble() / max(source.width, source.height))
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val canvas = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (scaled !== source) scaled.recycle()
        source.recycle()
        try {
            val sx = width.toFloat() / (if (screenWidth > 0) screenWidth else width)
            val sy = height.toFloat() / (if (screenHeight > 0) screenHeight else height)
            val draw = Canvas(canvas)
            val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(2f, width / 400f) }
            val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = max(14f, width / 38f)
                isFakeBoldText = true
            }
            marks.forEachIndexed { index, mark ->
                val color = palette[index % palette.size]
                val rect = RectF(mark.left * sx, mark.top * sy, mark.right * sx, mark.bottom * sy)
                box.color = color
                draw.drawRect(rect, box)
                val label = mark.ref
                val labelWidth = text.measureText(label) + 8f
                val labelHeight = text.textSize + 6f
                // Tag sits just above the box, or inside its top edge when there is no room above.
                val top = if (rect.top - labelHeight >= 0) rect.top - labelHeight else rect.top
                tag.color = color
                draw.drawRect(rect.left, top, rect.left + labelWidth, top + labelHeight, tag)
                draw.drawText(label, rect.left + 4f, top + labelHeight - 5f, text)
            }
            val out = ByteArrayOutputStream()
            canvas.compress(Bitmap.CompressFormat.JPEG, 80, out)
            return MindImage("data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), width, height)
        } finally {
            canvas.recycle()
        }
    }
}
