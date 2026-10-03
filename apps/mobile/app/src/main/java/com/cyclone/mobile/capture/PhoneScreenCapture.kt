package com.cyclone.mobile.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.cyclone.mobile.CycloneAccessibilityService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-demand screen capture served over the Cyclone gateway. Uses the accessibility service's
 * native screenshot primitive (`android:canTakeScreenshot="true"`, minSdk 34), then optionally
 * scales to a compact frame and returns base64 evidence. A busy guard keeps concurrent gateway
 * requests from issuing overlapping captures, which Android rejects.
 */
object PhoneScreenCapture {
    class ScreenCaptureException(val code: String, message: String) : IllegalStateException(message)

    const val CAPTURE_TIMEOUT_MS = 10_000L
    const val DEFAULT_EVIDENCE_MAX_DIMENSION = 480
    const val MAX_BASE64_PNG_BYTES = 900_000
    const val SOURCE_ACCESSIBILITY = "accessibility-service"

    private val busy = AtomicBoolean(false)

    data class ScaleTarget(val width: Int, val height: Int) {
        val isScaling: Boolean get() = width > 0 && height > 0

        companion object {
            val NONE = ScaleTarget(-1, -1)
        }
    }

    fun scaleToMaxDimension(width: Int, height: Int, maxDimension: Int?): ScaleTarget {
        if (maxDimension == null || maxDimension <= 0 || width <= 0 || height <= 0) return ScaleTarget.NONE
        val longest = maxOf(width, height)
        if (longest <= maxDimension) return ScaleTarget.NONE
        val scale = maxDimension.toDouble() / longest
        return ScaleTarget(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    }

    fun canIncludeBase64(pngBytes: Int, capBytes: Int = MAX_BASE64_PNG_BYTES): Boolean =
        pngBytes > 0 && pngBytes <= capBytes

    fun capture(
        service: CycloneAccessibilityService?,
        maxDimension: Int? = null,
        includeBase64: Boolean = false,
    ): JSONObject {
        if (service == null) {
            throw ScreenCaptureException("ACCESSIBILITY_NOT_CONNECTED", "Cyclone Accessibility is not connected")
        }
        if (!busy.compareAndSet(false, true)) {
            throw ScreenCaptureException("SCREENSHOT_BUSY", "Another screen capture is already in progress")
        }
        try {
            val latch = CountDownLatch(1)
            val outcome = AtomicReference<Result<JSONObject>?>()
            val deliver: (Result<CycloneAccessibilityService.ScreenshotArtifact>) -> Unit = { result ->
                outcome.set(result.mapCatching { artifact ->
                    val scale = scaleToMaxDimension(artifact.width, artifact.height, maxDimension)
                    val (bytes, outWidth, outHeight) = if (scale.isScaling) {
                        val decoded = BitmapFactory.decodeFile(artifact.file.absolutePath)
                            ?: error("Unable to decode captured screenshot for scaling")
                        val scaled = Bitmap.createScaledBitmap(decoded, scale.width, scale.height, true)
                        val stream = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
                        Triple(stream.toByteArray(), scale.width, scale.height)
                    } else {
                        Triple(artifact.file.readBytes(), artifact.width, artifact.height)
                    }
                    val encoded = if (includeBase64 && canIncludeBase64(bytes.size)) {
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } else null
                    payload(
                        source = artifact.liveFrame?.source?.name ?: SOURCE_ACCESSIBILITY,
                        filePath = artifact.file.absolutePath,
                        width = outWidth,
                        height = outHeight,
                        bytes = bytes.size,
                        timestampMs = artifact.timestampMs,
                        crop = artifact.crop?.toJson() ?: JSONObject.NULL,
                        scaled = scale.isScaling,
                        includeBase64 = includeBase64,
                        pngBase64 = encoded,
                    )
                })
                latch.countDown()
            }
            val live = com.cyclone.mobile.ai.vision.live.LiveVisionRuntime.capture(service.cacheDir)
            if (live != null) deliver(Result.success(live)) else service.takeScreenshot(null, deliver)
            if (!latch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw ScreenCaptureException("SCREENSHOT_TIMEOUT", "Screen capture did not finish in time")
            }
            return outcome.get()?.getOrElse { error ->
                throw ScreenCaptureException("SCREENSHOT_FAILED", error.message ?: "Screen capture failed")
            } ?: throw ScreenCaptureException("SCREENSHOT_FAILED", "Screen capture returned no result")
        } finally {
            busy.set(false)
        }
    }

    /** Pure frame payload builder; unit-tested without Android. */
    internal fun payload(
        source: String,
        filePath: String,
        width: Int,
        height: Int,
        bytes: Int,
        timestampMs: Long,
        crop: Any,
        scaled: Boolean,
        includeBase64: Boolean,
        pngBase64: String?,
    ): JSONObject {
        val omitted = includeBase64 && pngBase64 == null
        return JSONObject()
            .put("source", source)
            .put("filePath", filePath)
            .put("width", width)
            .put("height", height)
            .put("bytes", bytes)
            .put("timestampMs", timestampMs)
            .put("crop", crop)
            .put("scaled", scaled)
            .put("pngBase64", pngBase64 ?: JSONObject.NULL)
            .put("base64Omitted", if (omitted) "TOO_LARGE" else JSONObject.NULL)
    }
}
