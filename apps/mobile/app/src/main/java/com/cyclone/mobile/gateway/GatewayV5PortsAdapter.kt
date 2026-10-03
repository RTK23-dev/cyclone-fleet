package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.ports.PortOutbox
import com.cyclone.mobile.secrets.SealedDelivery
import org.json.JSONObject

/**
 * Plan 48 run 4: Cyclone Ports over the gateway. The phone never calls the PC; the PC's Port Hub polls the phone's
 * [PortOutbox]:
 * - `ports.poll {ack?, max?, drop?}` → `{items}`: what runs sent and the waits they opened;
 * - `ports.blob {id, offset}` → `{data, bytes, done}`: a screenshot, in chunks;
 * - `ports.answer {id, state, …}` → `{handled}`: what a plugin delivered for a wait. A code comes sealed to this
 *   phone's key and is opened here ([SealedDelivery.openCode]); the run never sees it;
 * - `ports.file {taskId: "pt_…", …}`: a file a plugin delivered (image, video, audio), in `cc.media` chunks, to the
 *   gallery.
 */
internal object GatewayV5PortsAdapter {
    @Volatile private var installed = false

    /** Seam for JVM tests. */
    internal var outbox: PortOutbox = PortOutbox.shared

    fun install(context: Context) {
        if (installed) return
        SealedDelivery.install(context)
        CommandMedia.install(context)
        outbox.openCode = { run, place, sealed -> SealedDelivery.openCode(run.runId, run.runId, place, sealed) }
        installed = true
    }

    fun dispatch(op: String, args: JSONObject): JSONObject = try {
        when (op) {
            "ports.poll" -> outbox.poll(args)
            "ports.blob" -> {
                if (args.keys().asSequence().toSet() != setOf("id", "offset")) throw IllegalArgumentException("ports.blob takes {id, offset}.")
                outbox.blob(args)
            }
            "ports.answer" -> outbox.answer(args)
            "ports.file" -> {
                if (!args.optString("taskId").startsWith("pt_")) throw IllegalArgumentException("ports.file takes a port item id.")
                CommandMedia.receive(args)
            }
            else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported ports operation: $op")
        }
    } catch (error: IllegalArgumentException) {
        throw GatewayProtocolException("INVALID_REQUEST", error.message ?: "Bad ports request.")
    } catch (error: NoSuchElementException) {
        throw GatewayProtocolException("INVALID_REQUEST", error.message ?: "No such item.")
    }
}
