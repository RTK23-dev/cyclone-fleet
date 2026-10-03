package com.cyclone.mobile.ui.v32.ask

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.cyclone.mobile.R

/**
 * The AI page's scene (R3, alpha.70): a tiny video (`res/raw/ask_scene.mp4`, 80 × 144, under 1 MB) that carries, for
 * every digit cell, the light and colour the rain shows. It is not the picture on screen: the rain shader draws
 * Cyclone's digits at full resolution and only reads each cell's light (the brightest channel) and colour from here.
 * Baked by `docs/design/redesign/rounds/R3/bake_scene.py`.
 *
 * One background thread decodes it in a loop at its own 30 fps, into three small bitmaps it takes turns on, and
 * publishes the newest in [frame]. It runs only while the page is on screen, stops when the app is in the background,
 * and with Android animations off decodes one frame and stops. If the phone cannot decode it, [failed] is set and
 * the page keeps the drawn dome.
 */
internal class AskScene(private val context: Context) {
    @Volatile var frame: Bitmap? = null
        private set
    @Volatile var failed = false
        private set
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(still: Boolean) {
        if (running || failed) return
        running = true
        thread = Thread({
            try {
                decode(still)
            } catch (_: InterruptedException) {
                // Stopped while waiting for the next frame.
            } catch (_: Throwable) {
                failed = true
            }
        }, "cyclone-ask-scene").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(500) }
        thread = null
    }

    private fun decode(still: Boolean) {
        val file = context.resources.openRawResourceFd(R.raw.ask_scene)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.fileDescriptor, file.startOffset, file.length)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            val bitmaps = arrayOfNulls<Bitmap>(3)
            var next = 0
            var pixels = IntArray(0)
            var base = System.nanoTime()
            var inputDone = false
            while (running) {
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if (input >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(input)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(input, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if (output < 0) continue
                val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (info.size > 0) {
                    decoder.getOutputImage(output)?.use { image ->
                        val wait = base + info.presentationTimeUs * 1_000 - System.nanoTime()
                        if (wait > 0 && !still) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        val w = image.cropRect.width()
                        val h = image.cropRect.height()
                        if (pixels.size != w * h) pixels = IntArray(w * h)
                        toArgb(image, pixels)
                        val target = bitmaps[next]?.takeIf { it.width == w && it.height == h }
                            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmaps[next] = it }
                        target.setPixels(pixels, 0, w, 0, 0, w, h)
                        frame = target
                        next = (next + 1) % bitmaps.size
                    }
                }
                decoder.releaseOutputBuffer(output, false)
                if (still && frame != null) break
                if (end) {
                    // Loop: back to the first frame, on a fresh clock.
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    decoder.flush()
                    inputDone = false
                    base = System.nanoTime()
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            runCatching { file.close() }
        }
    }

    private fun toArgb(image: Image, out: IntArray) {
        val crop = image.cropRect
        val (yPlane, uPlane, vPlane) = image.planes
        val y = yPlane.buffer
        val u = uPlane.buffer
        val v = vPlane.buffer
        var i = 0
        for (row in 0 until crop.height()) {
            val py = crop.top + row
            for (col in 0 until crop.width()) {
                val px = crop.left + col
                val luma = y.get(py * yPlane.rowStride + px * yPlane.pixelStride).toInt() and 0xFF
                val chroma = (py / 2) to (px / 2)
                val cb = u.get(chroma.first * uPlane.rowStride + chroma.second * uPlane.pixelStride).toInt() and 0xFF
                val cr = v.get(chroma.first * vPlane.rowStride + chroma.second * vPlane.pixelStride).toInt() and 0xFF
                out[i++] = AskSceneColor.bt709(luma, cb, cr)
            }
        }
    }

}
