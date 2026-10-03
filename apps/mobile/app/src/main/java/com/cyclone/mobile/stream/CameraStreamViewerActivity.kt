package com.cyclone.mobile.stream

import android.app.Activity
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen receiver for Cyclone One's one-to-many camera stream.
 *
 * Width and height metadata from the source are authoritative. Presentation is contain-only: a
 * 4:3 source remains 4:3 and a 3:4 source remains 3:4, even on a tall 9:16 or 20:9 phone display.
 */
class CameraStreamViewerActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var surfaceView: NativeAspectSurfaceView
    private lateinit var statusView: TextView
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private var decoder: H264ViewerDecoder? = null
    private var streamUrl: String = ""
    private var streamToken: String = ""
    private var streamTarget: String = ""
    private val finishingFromStream = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveViewer()
        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        streamToken = intent.getStringExtra(EXTRA_STREAM_TOKEN).orEmpty()
        streamTarget = intent.getStringExtra(EXTRA_STREAM_TARGET).orEmpty()
        if (!isSafeLoopbackStream(streamUrl) || !isSafeViewerToken(streamToken) || !isSafeViewerTarget(streamTarget)) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surfaceView = NativeAspectSurfaceView(this).also { view ->
            view.holder.addCallback(this)
            view.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            )
        }
        statusView = TextView(this).apply {
            text = "Connecting camera…"
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            textSize = 13f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = dp(18) }
        }
        root.addView(surfaceView)
        root.addView(statusView)
        setContentView(root)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (socket != null) return
        decoder = H264ViewerDecoder(
            surface = holder.surface,
            onVideoSize = { width, height ->
                runOnUiThread {
                    surfaceView.setVideoSize(width, height)
                    showStatus("LIVE · ${width}×${height} · source frame", autoHide = true)
                }
            },
            onDecoderError = { message ->
                runOnUiThread {
                    showStatus(message)
                    socket?.close(1011, "decoder unavailable")
                    finishAfterStatus()
                }
            },
        )
        val request = Request.Builder()
            .url(streamUrl)
            .addHeader(VIEWER_TOKEN_HEADER, streamToken)
            .addHeader(VIEWER_TARGET_HEADER, streamTarget)
            .build()
        socket = client.newWebSocket(request, StreamListener())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        socket?.close(1000, "surface destroyed")
        socket = null
        decoder?.close()
        decoder = null
    }

    override fun onDestroy() {
        socket?.cancel()
        socket = null
        decoder?.close()
        decoder = null
        client.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    private inner class StreamListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            runOnUiThread { showStatus("Camera connected · waiting for first frame…") }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val json = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (json.optString("type")) {
                "hello" -> {
                    if (!json.optBoolean("preserveSourceAspect", false)) {
                        runOnUiThread { showStatus("Stream rejected · source aspect is not protected") }
                        webSocket.close(1003, "source aspect required")
                    }
                }
                "session" -> {
                    val width = json.optInt("width")
                    val height = json.optInt("height")
                    if (width > 0 && height > 0) decoder?.setFormat(width, height)
                }
                "state" -> handleState(json.optString("state"), webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (bytes.size < PACKET_HEADER_BYTES) return
            val data = bytes.toByteArray()
            val flags = data[0].toInt() and 0xff
            val ptsUs = ByteBuffer.wrap(data, 1, 8).long
            decoder?.offer(
                EncodedPacket(
                    payload = data.copyOfRange(PACKET_HEADER_BYTES, data.size),
                    ptsUs = ptsUs,
                    codecConfig = flags and FLAG_CONFIG != 0,
                    keyFrame = flags and FLAG_KEYFRAME != 0,
                ),
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            runOnUiThread {
                showStatus("Camera stream disconnected")
                finishAfterStatus()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val copy = if (code == 1013) "This phone fell behind · restart the stream from Cyclone One" else "Camera stream ended"
                showStatus(copy)
                finishAfterStatus()
            }
        }
    }

    private fun handleState(state: String, webSocket: WebSocket) {
        when (state) {
            "STARTING" -> runOnUiThread { showStatus("Starting camera…") }
            "WAITING_KEYFRAME" -> runOnUiThread { showStatus("Camera ready · synchronizing video…") }
            "RECONNECTING" -> runOnUiThread { showStatus("Camera reconnecting…") }
            "SLEEPING" -> runOnUiThread { showStatus("Source phone is sleeping") }
            "UNAVAILABLE" -> {
                runOnUiThread {
                    showStatus("Camera unavailable on the source phone")
                    finishAfterStatus()
                }
                webSocket.close(1011, "source unavailable")
            }
            "STOPPED" -> {
                runOnUiThread {
                    showStatus("Camera stream stopped")
                    finishAfterStatus()
                }
                webSocket.close(1000, "source stopped")
            }
        }
    }

    private fun showStatus(copy: String, autoHide: Boolean = false) {
        statusView.animate().cancel()
        statusView.alpha = 1f
        statusView.text = copy
        if (autoHide) {
            statusView.postDelayed({
                if (!isFinishing) statusView.animate().alpha(0f).setDuration(220).start()
            }, 1800)
        }
    }

    private fun finishAfterStatus() {
        if (!finishingFromStream.compareAndSet(false, true)) return
        statusView.postDelayed({ if (!isFinishing) finish() }, 900)
    }

    private fun enterImmersiveViewer() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun isSafeLoopbackStream(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        if (uri.scheme != "ws" || uri.host != "127.0.0.1" || uri.port != PHONE_REVERSE_PORT) return false
        if (uri.query != null || uri.fragment != null || uri.userInfo != null) return false
        val segments = uri.pathSegments
        return segments.size == 4 &&
            segments[0] == "v1" &&
            segments[1] == "camera-stream" &&
            segments[2] == "ws" &&
            segments[3].isNotBlank()
    }

    private fun isSafeViewerToken(value: String): Boolean =
        value.length in 16..128 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isSafeViewerTarget(value: String): Boolean =
        value.isNotBlank() && value.length <= 160 && value.none { it == '\r' || it == '\n' }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_STREAM_URL = "cyclone_stream_url"
        const val EXTRA_STREAM_TOKEN = "cyclone_stream_token"
        const val EXTRA_STREAM_TARGET = "cyclone_stream_target"
        private const val VIEWER_TOKEN_HEADER = "X-Cyclone-Viewer-Token"
        private const val VIEWER_TARGET_HEADER = "X-Cyclone-Viewer-Target"
        private const val PHONE_REVERSE_PORT = 17881
        private const val PACKET_HEADER_BYTES = 9
        private const val FLAG_CONFIG = 0x01
        private const val FLAG_KEYFRAME = 0x02
    }
}

/** MediaCodec worker whose failure is always surfaced to the UI instead of becoming a black screen. */
private class H264ViewerDecoder(
    private val surface: Surface,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onDecoderError: (String) -> Unit,
) : AutoCloseable {
    private val packets = H264PacketBuffer(30)
    private val running = AtomicBoolean(true)
    private val restartRequested = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    @Volatile private var width = 0
    @Volatile private var height = 0
    private var codec: MediaCodec? = null
    private val worker = Thread(::runDecoder, "cyclone-camera-viewer").apply {
        isDaemon = true
        start()
    }

    fun setFormat(nextWidth: Int, nextHeight: Int) {
        if (nextWidth <= 0 || nextHeight <= 0) return
        val changed = width > 0 && height > 0 && (width != nextWidth || height != nextHeight)
        width = nextWidth
        height = nextHeight
        if (changed) {
            restartRequested.set(true)
            packets.resetToBootstrap()
        }
        onVideoSize(nextWidth, nextHeight)
    }

    fun offer(packet: EncodedPacket) {
        if (running.get()) packets.offer(packet)
    }

    private fun runDecoder() {
        try {
            while (running.get()) {
                if (restartRequested.getAndSet(false)) releaseCodec()
                if (codec == null) {
                    if (width <= 0 || height <= 0) {
                        Thread.sleep(8)
                        continue
                    }
                    codec = MediaCodec.createDecoderByType(MIME).also { decoder ->
                        val format = MediaFormat.createVideoFormat(MIME, width, height)
                        decoder.configure(format, surface, null, 0)
                        decoder.start()
                    }
                }

                val decoder = codec ?: continue
                packets.poll(200)?.let { queuePacket(decoder, it) }
                drain(decoder)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            if (running.get() && errorReported.compareAndSet(false, true)) {
                onDecoderError("Video decoder stopped · restart the camera stream")
            }
        } finally {
            releaseCodec()
        }
    }

    private fun queuePacket(decoder: MediaCodec, packet: EncodedPacket) {
        val inputIndex = decoder.dequeueInputBuffer(4_000)
        if (inputIndex < 0) return
        val input = decoder.getInputBuffer(inputIndex)
            ?: throw IllegalStateException("H.264 decoder returned no input buffer")
        input.clear()
        if (packet.payload.size > input.remaining()) {
            throw IllegalStateException("H.264 packet exceeds decoder input capacity")
        }
        input.put(packet.payload)
        val flags = when {
            packet.codecConfig -> MediaCodec.BUFFER_FLAG_CODEC_CONFIG
            packet.keyFrame -> MediaCodec.BUFFER_FLAG_KEY_FRAME
            else -> 0
        }
        decoder.queueInputBuffer(inputIndex, 0, packet.payload.size, packet.ptsUs, flags)
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val output = decoder.outputFormat
                    val nextWidth = output.getInteger(MediaFormat.KEY_WIDTH)
                    val nextHeight = output.getInteger(MediaFormat.KEY_HEIGHT)
                    if (nextWidth > 0 && nextHeight > 0) onVideoSize(nextWidth, nextHeight)
                }
                else -> if (outputIndex >= 0) decoder.releaseOutputBuffer(outputIndex, true)
            }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        packets.clear()
        worker.interrupt()
        runCatching { worker.join(700) }
        releaseCodec()
    }

    @Synchronized
    private fun releaseCodec() {
        val decoder = codec ?: return
        codec = null
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
    }

    companion object {
        private const val MIME = "video/avc"
    }
}

/** SurfaceView whose measured bounds always contain the source frame without changing its ratio. */
private class NativeAspectSurfaceView(activity: Activity) : SurfaceView(activity) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = View.MeasureSpec.getSize(heightMeasureSpec)
        val (measuredWidth, measuredHeight) = fitNativeAspect(
            videoWidth,
            videoHeight,
            availableWidth,
            availableHeight,
        )
        setMeasuredDimension(measuredWidth, measuredHeight)
    }
}
