package com.cyclone.mobile.stream

import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/** Encoded H.264 packet carried by Cyclone's camera WebSocket protocol. */
internal data class EncodedPacket(
    val payload: ByteArray,
    val ptsUs: Long,
    val codecConfig: Boolean,
    val keyFrame: Boolean,
)

/**
 * Low-latency H.264 packet buffer.
 *
 * Generic FIFO eviction is unsafe for H.264: dropping SPS/PPS or the only keyframe can leave an
 * otherwise healthy viewer black indefinitely. This buffer remembers the latest decoder config
 * and keyframe, and when pressure occurs it collapses backlog back to that decodable bootstrap.
 */
internal class H264PacketBuffer(private val capacity: Int = 30) {
    private val queue = LinkedBlockingDeque<EncodedPacket>(capacity.coerceAtLeast(3))
    private val lock = Any()
    private var latestConfig: EncodedPacket? = null
    private var latestKeyframe: EncodedPacket? = null

    fun offer(packet: EncodedPacket) {
        synchronized(lock) {
            when {
                packet.codecConfig -> {
                    latestConfig = packet
                    // A new SPS/PPS configuration makes every keyframe encoded under the previous
                    // configuration unsafe to reuse. Wait for a keyframe that belongs to this config.
                    latestKeyframe = null
                    queue.clear()
                    queue.offerLast(packet)
                }
                packet.keyFrame -> {
                    latestKeyframe = packet
                    // A keyframe is a natural latency reset point. Old interframes are never more
                    // valuable than starting immediately from the newest decodable frame.
                    queue.clear()
                    latestConfig?.let(queue::offerLast)
                    queue.offerLast(packet)
                }
                queue.offerLast(packet) -> Unit
                else -> {
                    // The decoder is behind. Rebuild only from a *decodable* bootstrap. If a new
                    // config has arrived but its keyframe has not, discard interframes until it does.
                    queue.clear()
                    latestConfig?.let(queue::offerLast)
                    val keyframe = latestKeyframe
                    if (keyframe != null) {
                        queue.offerLast(keyframe)
                        queue.offerLast(packet)
                    }
                }
            }
        }
    }

    fun poll(timeoutMs: Long): EncodedPacket? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun resetToBootstrap() {
        synchronized(lock) {
            queue.clear()
            latestConfig?.let(queue::offerLast)
            latestKeyframe?.let(queue::offerLast)
        }
    }

    fun clear() {
        synchronized(lock) {
            queue.clear()
            latestConfig = null
            latestKeyframe = null
        }
    }

    internal fun snapshotForTest(): List<EncodedPacket> = queue.toList()
}

/** Return the largest contain-fit bounds without ever changing the source aspect ratio. */
internal fun fitNativeAspect(
    videoWidth: Int,
    videoHeight: Int,
    availableWidth: Int,
    availableHeight: Int,
): Pair<Int, Int> {
    if (videoWidth <= 0 || videoHeight <= 0 || availableWidth <= 0 || availableHeight <= 0) {
        return availableWidth.coerceAtLeast(0) to availableHeight.coerceAtLeast(0)
    }
    val sourceRatio = videoWidth.toDouble() / videoHeight.toDouble()
    val boxRatio = availableWidth.toDouble() / availableHeight.toDouble()
    return if (boxRatio > sourceRatio) {
        val height = availableHeight
        (height * sourceRatio).toInt().coerceAtLeast(1) to height.coerceAtLeast(1)
    } else {
        val width = availableWidth
        width.coerceAtLeast(1) to (width / sourceRatio).toInt().coerceAtLeast(1)
    }
}
