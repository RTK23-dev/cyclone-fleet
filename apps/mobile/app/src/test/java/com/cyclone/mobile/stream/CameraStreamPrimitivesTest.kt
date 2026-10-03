package com.cyclone.mobile.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamPrimitivesTest {
    @Test
    fun fitNativeAspectKeepsFourByThreeInsideTallPhone() {
        val (width, height) = fitNativeAspect(1920, 1440, 1080, 2400)
        assertEquals(1080, width)
        assertEquals(810, height)
        assertEquals(4.0 / 3.0, width.toDouble() / height.toDouble(), 0.001)
    }

    @Test
    fun fitNativeAspectKeepsPortraitSensorInsideWideBox() {
        val (width, height) = fitNativeAspect(1440, 1920, 2400, 1080)
        assertEquals(810, width)
        assertEquals(1080, height)
        assertEquals(3.0 / 4.0, width.toDouble() / height.toDouble(), 0.001)
    }

    @Test
    fun packetPressureNeverEvictsDecoderBootstrap() {
        val buffer = H264PacketBuffer(capacity = 4)
        val config = packet(1, config = true)
        val key = packet(2, key = true)
        buffer.offer(config)
        buffer.offer(key)
        for (index in 3..20) buffer.offer(packet(index))

        val snapshot = buffer.snapshotForTest()
        assertTrue(snapshot.any { it.codecConfig })
        assertTrue(snapshot.any { it.keyFrame })
        assertTrue(snapshot.last().ptsUs >= 20L)
    }

    @Test
    fun newerKeyframeDropsOldLatencyButKeepsConfig() {
        val buffer = H264PacketBuffer(capacity = 10)
        buffer.offer(packet(1, config = true))
        buffer.offer(packet(2, key = true))
        for (index in 3..7) buffer.offer(packet(index))
        buffer.offer(packet(8, key = true))

        val snapshot = buffer.snapshotForTest()
        assertEquals(2, snapshot.size)
        assertTrue(snapshot.first().codecConfig)
        assertTrue(snapshot.last().keyFrame)
        assertEquals(8L, snapshot.last().ptsUs)
        assertFalse(snapshot.any { it.ptsUs in 2L..7L })
    }

    @Test
    fun newCodecConfigInvalidatesOldKeyframe() {
        val buffer = H264PacketBuffer(capacity = 4)
        buffer.offer(packet(1, config = true))
        buffer.offer(packet(2, key = true))
        buffer.offer(packet(10, config = true))
        for (index in 11..20) buffer.offer(packet(index))

        val snapshot = buffer.snapshotForTest()
        assertTrue(snapshot.any { it.codecConfig && it.ptsUs == 10L })
        assertFalse(snapshot.any { it.keyFrame && it.ptsUs == 2L })
    }

    private fun packet(index: Int, config: Boolean = false, key: Boolean = false) = EncodedPacket(
        payload = byteArrayOf(index.toByte()),
        ptsUs = index.toLong(),
        codecConfig = config,
        keyFrame = key,
    )
}
