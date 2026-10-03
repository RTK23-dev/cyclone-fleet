package com.cyclone.mobile.gateway

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.cyclone.mobile.CycloneAccessibilityService
import com.cyclone.mobile.ai.vision.live.LiveVisionRuntime
import com.cyclone.mobile.applearner.PageAwarenessRuntime
import com.cyclone.mobile.capture.PhoneScreenCapture
import com.cyclone.mobile.capture.PhoneScreenCapture.ScreenCaptureException
import com.cyclone.mobile.runtime.session.ExecutionRequestScope
import com.cyclone.mobile.runtime.session.ExecutionSession
import com.cyclone.mobile.runtime.session.SessionContract
import com.cyclone.mobile.runtime.session.SessionIdentityException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Serves an on-demand Android screen frame over the gateway. The accessibility service screenshot
 * primitive is used (no MediaProjection service required); a compact scaled frame plus optional
 * base64 evidence is returned, and the frame path is attached to the current page for previews.
 */
internal object GatewayCaptureAdapter {
    fun capture(context: Context, args: JSONObject): JSONObject {
        val merged = ExecutionRequestScope.merge(args, args.optJSONObject("params") ?: JSONObject())
        if (args.has("workspaceId") && !merged.has("workspaceId")) merged.put("workspaceId", args.get("workspaceId"))
        if (args.has("workspaceGeneration") && !merged.has("workspaceGeneration")) {
            merged.put("workspaceGeneration", args.get("workspaceGeneration"))
        }
        val plane = try {
            SessionContract.classify(merged)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch")
        }
        val execution = try {
            ExecutionRequestScope.bind(merged)
        } catch (error: SessionIdentityException) {
            throw GatewayProtocolException(error.errorClass, error.message ?: "session/display mismatch")
        }
        val maxDimension = args.optInt("maxDimension", 0).takeIf { it > 0 }
        val includeBase64 = args.optBoolean("includeBase64", false)
        val frame = if (execution.sessionId == ExecutionSession.DEFAULT_FOREGROUND_SESSION_ID) {
            val service = CycloneAccessibilityService.instance
                ?: throw GatewayProtocolException("ACCESSIBILITY_NOT_CONNECTED", "Cyclone Accessibility is not connected")
            try {
                PhoneScreenCapture.capture(service, maxDimension, includeBase64)
            } catch (error: ScreenCaptureException) {
                throw GatewayProtocolException(error.code, error.message ?: "Screen capture failed")
            }
        } else {
            val artifact = LiveVisionRuntime.capture(context.cacheDir, sessionId = execution.sessionId)
                ?: throw GatewayProtocolException("FRAME_STREAM_STALLED", "no fresh frame from the requested display")
            encodeArtifact(artifact, maxDimension, includeBase64)
        }
        val pageKey = try {
            GatewayObservationStore.current(execution)?.page?.pageKey
        } catch (_: SessionIdentityException) {
            null
        }
        val filePath = frame.optString("filePath").takeIf { it.isNotBlank() }
        if (pageKey != null && filePath != null) {
            runCatching { PageAwarenessRuntime.store.attachPreview(pageKey, filePath) }
        }
        val payload = frame.put("sessionId", execution.sessionId).put("displayId", execution.displayId)
        return SessionContract.attach(payload, plane)
    }

    private fun encodeArtifact(
        artifact: CycloneAccessibilityService.ScreenshotArtifact,
        maxDimension: Int?,
        includeBase64: Boolean,
    ): JSONObject {
        val scale = PhoneScreenCapture.scaleToMaxDimension(artifact.width, artifact.height, maxDimension)
        val (bytes, outWidth, outHeight) = if (scale.isScaling) {
            val decoded = BitmapFactory.decodeFile(artifact.file.absolutePath)
                ?: throw GatewayProtocolException("CAPABILITY_UNAVAILABLE", "Unable to decode workspace frame")
            val scaled = Bitmap.createScaledBitmap(decoded, scale.width, scale.height, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
            Triple(stream.toByteArray(), scale.width, scale.height)
        } else {
            Triple(artifact.file.readBytes(), artifact.width, artifact.height)
        }
        val encoded = if (includeBase64 && PhoneScreenCapture.canIncludeBase64(bytes.size)) {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else null
        return PhoneScreenCapture.payload(
            source = artifact.liveFrame?.source?.name ?: "VIRTUAL_DISPLAY_SURFACE",
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
    }
}
