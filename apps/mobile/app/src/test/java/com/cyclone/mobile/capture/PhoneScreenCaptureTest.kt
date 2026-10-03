package com.cyclone.mobile.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneScreenCaptureTest {
    @Test
    fun scaleKeepsAspectRatioWithinMaxDimension() {
        val target = PhoneScreenCapture.scaleToMaxDimension(1080, 2400, 480)
        assertTrue(target.isScaling)
        assertEquals(216, target.width)
        assertEquals(480, target.height)
    }

    @Test
    fun scaleIsNoopWhenAlreadyWithinLimitOrMaxMissing() {
        assertFalse(PhoneScreenCapture.scaleToMaxDimension(400, 400, 480).isScaling)
        assertFalse(PhoneScreenCapture.scaleToMaxDimension(1080, 2400, null).isScaling)
        assertFalse(PhoneScreenCapture.scaleToMaxDimension(0, 2400, 480).isScaling)
    }

    @Test
    fun base64AllowedOnlyWithinByteCap() {
        assertTrue(PhoneScreenCapture.canIncludeBase64(100, 900_000))
        assertTrue(PhoneScreenCapture.canIncludeBase64(900_000, 900_000))
        assertFalse(PhoneScreenCapture.canIncludeBase64(900_001, 900_000))
        assertFalse(PhoneScreenCapture.canIncludeBase64(0, 900_000))
    }

    @Test
    fun payloadCarriesFrameMetadataAndBase64State() {
        val withImage = PhoneScreenCapture.payload(
            source = "accessibility-service",
            filePath = "/cache/cyclone-1.png",
            width = 216,
            height = 480,
            bytes = 42_000,
            timestampMs = 1_700_000_000_000,
            crop = JSONObject.NULL,
            scaled = true,
            includeBase64 = true,
            pngBase64 = "aW1hZ2U=",
        )
        assertEquals("accessibility-service", withImage.getString("source"))
        assertEquals(216, withImage.getInt("width"))
        assertEquals(480, withImage.getInt("height"))
        assertEquals(42_000, withImage.getInt("bytes"))
        assertEquals("aW1hZ2U=", withImage.getString("pngBase64"))
        assertTrue(withImage.isNull("base64Omitted"))

        val omitted = PhoneScreenCapture.payload(
            source = "accessibility-service",
            filePath = "/cache/cyclone-2.png",
            width = 1080,
            height = 2400,
            bytes = 1_200_000,
            timestampMs = 1_700_000_000_001,
            crop = JSONObject.NULL,
            scaled = false,
            includeBase64 = true,
            pngBase64 = null,
        )
        assertTrue(omitted.isNull("pngBase64"))
        assertEquals("TOO_LARGE", omitted.getString("base64Omitted"))
    }
}
