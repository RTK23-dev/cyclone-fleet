package com.cyclone.mobile.share

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serves the phone's screen to trusted PCs over Wi-Fi (see [LanShareProtocol]). View-only: it reads frames and writes
 * them out; it never accepts input. Runs only while the owner shares the whole screen (MediaProjection consent and
 * its ongoing notification) and stops with it.
 */
class LanShareServer(
    private val trust: TrustLookup,
    private val identity: PhoneSigner,
    private val frames: FrameSource,
    private val bindAddress: InetAddress? = null,
    private val preferredPort: Int = DEFAULT_PORT,
    private val maxClients: Int = 2,
    private val frameIntervalMs: Long = 100,
    private val keepaliveMs: Long = 2_000,
    private val handshakeTimeoutMs: Int = 5_000,
) {
    /** A PC the phone trusts right now, or null (unknown or logged out). */
    fun interface TrustLookup {
        fun activePcPublicKey(trustId: String): String?
    }

    interface PhoneSigner {
        val phoneId: String
        fun sign(transcript: String): String
    }

    /** The latest screen frame as JPEG, or null when nothing new since [afterFrameId]. */
    fun interface FrameSource {
        fun next(afterFrameId: Long): Frame?
    }

    data class Frame(val frameId: Long, val jpeg: ByteArray, val width: Int, val height: Int)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val clients = AtomicInteger(0)
    private val sockets = java.util.Collections.synchronizedSet(mutableSetOf<Socket>())

    val port: Int get() = server?.localPort ?: 0
    val isRunning: Boolean get() = running

    @Synchronized
    fun start(): Int {
        if (running) return port
        val socket = try {
            ServerSocket(preferredPort, 4, bindAddress)
        } catch (_: SocketException) {
            ServerSocket(0, 4, bindAddress)
        }
        server = socket
        running = true
        Thread({ acceptLoop(socket) }, "cyclone-lan-share-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        synchronized(sockets) { sockets.toList() }.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(listener: ServerSocket) {
        while (running) {
            val socket = try {
                listener.accept()
            } catch (_: Exception) {
                break
            }
            if (clients.incrementAndGet() > maxClients) {
                clients.decrementAndGet()
                runCatching { socket.close() }
                continue
            }
            sockets += socket
            Thread({
                try {
                    serve(socket)
                } catch (_: Exception) {
                    // A failed handshake or a PC that went away: close quietly, never crash the phone.
                } finally {
                    sockets -= socket
                    clients.decrementAndGet()
                    runCatching { socket.close() }
                }
            }, "cyclone-lan-share-client").apply { isDaemon = true }.start()
        }
    }

    /** Handshake, then frames until the PC leaves or sharing stops. */
    internal fun serve(socket: Socket) {
        socket.soTimeout = handshakeTimeoutMs
        socket.tcpNoDelay = true
        val input = BufferedInputStream(socket.getInputStream())
        val output = DataOutputStream(socket.getOutputStream())
        val ephemeral = LanShareProtocol.newEphemeral()
        val phoneNonce = LanShareProtocol.nonce()
        val phoneEph = LanShareProtocol.publicKeyB64(ephemeral.public)
        writeLine(output, JSONObject()
            .put("v", LanShareProtocol.VERSION)
            .put("phoneId", identity.phoneId)
            .put("phoneNonce", phoneNonce)
            .put("phoneEph", phoneEph))

        val hello = JSONObject(readLine(input))
        val trustId = hello.optString("trustId")
        val pcNonce = hello.optString("pcNonce")
        val pcEph = hello.optString("pcEph")
        val pcSig = hello.optString("pcSig")
        if (trustId.isBlank() || pcNonce.length !in 16..64 || pcEph.isBlank() || pcSig.isBlank()) return refuse(output, "BAD_HELLO")
        val pcKey = trust.activePcPublicKey(trustId) ?: return refuse(output, "NOT_TRUSTED")
        val transcript = LanShareProtocol.transcript(identity.phoneId, trustId, phoneNonce, pcNonce, phoneEph, pcEph)
        if (!verify(pcKey, transcript, pcSig)) return refuse(output, "BAD_SIGNATURE")
        writeLine(output, JSONObject().put("ok", true).put("phoneSig", identity.sign(transcript)))

        val shared = LanShareProtocol.ecdh(ephemeral.private, LanShareProtocol.decodePublicKey(pcEph))
        val sealer = LanShareProtocol.Sealer(LanShareProtocol.deriveKeys(shared, transcript).phoneToPc)
        socket.soTimeout = 0
        var lastFrame = 0L
        var lastSent = System.currentTimeMillis()
        while (running && !socket.isClosed) {
            val frame = frames.next(lastFrame)
            val now = System.currentTimeMillis()
            if (frame != null) {
                lastFrame = frame.frameId
                writeRecord(output, sealer.seal(byteArrayOf(LanShareProtocol.TYPE_JPEG) + frame.jpeg))
                lastSent = now
            } else if (now - lastSent >= keepaliveMs) {
                val status = JSONObject().put("type", "keepalive").toString().toByteArray(Charsets.UTF_8)
                writeRecord(output, sealer.seal(byteArrayOf(LanShareProtocol.TYPE_STATUS) + status))
                lastSent = now
            }
            Thread.sleep(frameIntervalMs)
        }
    }

    private fun refuse(output: DataOutputStream, code: String) {
        runCatching { writeLine(output, JSONObject().put("ok", false).put("code", code)) }
    }

    private fun verify(pcPublicKey: String, transcript: String, signature: String): Boolean = runCatching {
        val verifier = java.security.Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(LanShareProtocol.decodePublicKey(pcPublicKey))
        verifier.update(transcript.toByteArray(Charsets.UTF_8))
        verifier.verify(LanShareProtocol.unb64(signature))
    }.getOrDefault(false)

    private fun writeLine(output: DataOutputStream, json: JSONObject) {
        output.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun writeRecord(output: DataOutputStream, sealed: ByteArray) {
        output.writeInt(sealed.size)
        output.write(sealed)
        output.flush()
    }

    private fun readLine(input: InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) throw SocketException("closed during handshake")
            if (next == '\n'.code) break
            bytes.write(next)
            if (bytes.size() > LanShareProtocol.MAX_LINE_BYTES) throw SocketException("handshake line too long")
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    companion object {
        const val DEFAULT_PORT = 47823
    }
}
