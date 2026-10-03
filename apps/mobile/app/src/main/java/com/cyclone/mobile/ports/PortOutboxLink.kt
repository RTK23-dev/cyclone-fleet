package com.cyclone.mobile.ports

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.mind.MindPortAnswer
import com.cyclone.mobile.mind.MindPortsLink
import com.cyclone.mobile.places.PlaceResolver
import org.json.JSONObject

/**
 * Plan 48 run 4: a mission's Cyclone Ports, through the phone's one [PortOutbox]. The run id is the mission id, so a
 * code the PC seals for this run opens only in this mission ([com.cyclone.mobile.secrets.SealedDelivery.openCode]).
 */
class PortOutboxLink(
    private val outbox: PortOutbox,
    private val runId: String,
    private val routine: String? = null,
    private val taskId: String? = null,
    private val placeOf: (AgentPageCard) -> String? = { PlaceResolver.resolveCurrent(it)?.id },
) : MindPortsLink {
    private fun run(app: String?) = PortOutbox.Run(runId, app, routine, taskId)
    override fun skills(app: String?): List<JSONObject> = outbox.skills(app, routine)
    override fun sendTo(plugin: String, port: String, data: JSONObject, image: ByteArray?, mime: String, app: String?): String? =
        outbox.emit(run(app).copy(plugin = plugin), port, data, image, mime)
    override fun waitFor(plugin: String, port: String, match: JSONObject, timeoutS: Int, app: String?, cancelled: () -> Boolean): MindPortAnswer =
        answer(outbox.awaitMatched(run(app).copy(plugin = plugin), port, match.optString("ask"), timeoutS, cancelled = cancelled, match = match))

    override fun send(port: String, data: JSONObject, image: ByteArray?, app: String?): String? =
        outbox.emit(run(app), port, data, image)

    override fun wait(port: String, ask: String, timeoutS: Int, place: String?, app: String?, cancelled: () -> Boolean): MindPortAnswer {
        val answer = outbox.await(run(app), port, ask, timeoutS, place, cancelled)
        return answer(answer)
    }

    private fun answer(answer: PortOutbox.Answer): MindPortAnswer = MindPortAnswer(
            state = answer.state,
            reason = answer.reason,
            value = answer.value,
            url = answer.url,
            fileName = answer.file?.optString("name")?.takeIf { it.isNotBlank() },
            folder = answer.file?.optString("folder")?.takeIf { it.isNotBlank() && it != "null" },
            codeLength = answer.codeLength,
            plugin = answer.plugin,
        )

    override fun place(page: AgentPageCard): String? = runCatching { placeOf(page) }.getOrNull()
}
