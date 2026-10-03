package com.cyclone.mobile.mind.modes

import android.content.Context
import com.cyclone.mobile.agent.contract.AgentActionEnvelope
import com.cyclone.mobile.agent.contract.AgentElementCandidate
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.agent.tools.CycloneAgentEnvironment
import com.cyclone.mobile.mind.MindDevicePort
import com.cyclone.mobile.mind.MindRefBook
import com.cyclone.mobile.mind.PhoneMindToolbox
import com.cyclone.mobile.mind.pilot.Pilot
import org.json.JSONObject

/**
 * Plan 42 (M4): Instant's hands on this phone. Every move goes through Cyclone's agent environment and so through
 * PhoneToolExecutor, with its approvals, access profile, settle and proof, exactly like the Mind's own taps. Direct
 * actions (contacts, timer, alarm, flashlight, volume, media) go through the executor's direct tools.
 */
internal class AndroidInstantHands(
    private val context: Context,
    private val goal: String,
    private val device: MindDevicePort,
    private val confirm: (String, Long) -> Boolean,
    private val cancelled: () -> Boolean,
) : InstantHands {
    private val env = CycloneAgentEnvironment(context, userTaskGoal = goal, ownerMission = true)
    private var page: AgentPageCard? = null
    private var controls: List<AgentElementCandidate> = emptyList()

    override fun look(withImage: Boolean): InstantScreen? {
        val observed = runCatching { if (withImage) env.observeWithImage(goal) else env.observe(goal) }.getOrNull() ?: return null
        val card = observed.page ?: return null
        page = card
        controls = env.allControls().ifEmpty { card.controls }
        val app = device.apps().firstOrNull { it.packageName == card.packageName }?.label ?: card.packageName
        // Code decides what is sensitive, never a model: a secret field on screen, or an app kept out of quick actions.
        val secret = controls.any { c ->
            c.evidence.optBoolean("password") || (c.evidence.optBoolean("editable") && PhoneMindToolbox.sensitive(MindRefBook.label(c)))
        }
        val sensitive = secret || Pilot.keepOff(card.packageName, app)
        val image = if (!withImage || sensitive) null else observed.image?.let { shot ->
            val png = shot.optString("pngBase64").takeIf { it.isNotBlank() } ?: return@let null
            val width = card.pageEvidence.optInt("captureWidth").takeIf { it > 0 } ?: shot.optInt("width")
            val height = card.pageEvidence.optInt("captureHeight").takeIf { it > 0 } ?: shot.optInt("height")
            com.cyclone.mobile.mind.mission.AndroidMindImageMarker.mark(png, emptyList(), width, height)?.dataUrl
        }
        return InstantScreen(app, controls.map { MindRefBook.label(it) }.filter { it.isNotBlank() }, sensitive, image)
    }

    override fun gesture(intent: InstantIntent, direction: String?): InstantMove {
        val card = current() ?: return InstantMove(false, false, "the screen couldn't be read")
        return when (intent) {
            InstantIntent.BACK -> move(env.act("phone.back", JSONObject(), "Instant: Back"))
            InstantIntent.HOME -> move(env.act("phone.home", JSONObject(), "Instant: Home"))
            InstantIntent.SCROLL -> move(env.act("phone.scroll", JSONObject().put("direction", if (direction == "up") "backward" else "forward"), "Instant: scroll"))
            InstantIntent.SWIPE -> {
                val width = card.pageEvidence.optInt("captureWidth").takeIf { it > 0 } ?: 1080
                val height = card.pageEvidence.optInt("captureHeight").takeIf { it > 0 } ?: 2400
                val cx = width / 2
                val cy = height / 2
                val dx = (width * 0.3).toInt()
                val dy = (height * 0.21).toInt()
                // "up" moves the finger up (the next video, the next page), like the owner's own thumb.
                val (x1, y1, x2, y2) = when (direction) {
                    "left" -> listOf(cx + dx, cy, cx - dx, cy)
                    "right" -> listOf(cx - dx, cy, cx + dx, cy)
                    "down" -> listOf(cx, cy - dy, cx, cy + dy)
                    else -> listOf(cx, cy + dy, cx, cy - dy)
                }
                move(env.act("phone.swipe", JSONObject().put("x1", x1).put("y1", y1).put("x2", x2).put("y2", y2)
                    .put("durationMs", 300).put("guard", true), "Instant: swipe $direction"))
            }
            else -> InstantMove(false, false, "Cyclone can't do ${intent.name.lowercase()} with a quick action yet")
        }
    }

    override fun tapLabel(label: String): InstantMove {
        if (page == null) fresh()
        val target = controls.firstOrNull { MindRefBook.label(it) == label } ?: run {
            fresh()
            controls.firstOrNull { MindRefBook.label(it) == label }
        } ?: return InstantMove(false, false, "\"$label\" is not on the screen")
        return move(env.act("phone.click", JSONObject().put("elementId", target.elementId), "Instant: tap \"$label\""))
    }

    override fun openApp(packageName: String): InstantMove {
        if (current() == null) return InstantMove(false, false, "the screen couldn't be read")
        return move(env.act("phone.open_app", JSONObject().put("package", packageName), "Instant: open $packageName"))
    }

    override fun camera(front: Boolean): InstantMove {
        if (current() == null) return InstantMove(false, false, "the screen couldn't be read")
        return move(env.act("phone.launch_intent", JSONObject().put("action", "camera").put("front", front), "Instant: open the camera"))
    }

    override fun contacts(name: String): List<InstantContact> {
        val found = LinkedHashMap<String, InstantContact>()
        for (query in (listOf(name) + (FAMILY[name.lowercase().trim()] ?: emptyList())).distinct()) {
            val result = device.direct("contacts_find", JSONObject().put("query", query))
            if (!result.ok) {
                // No access yet: the next mode asks for it (the Mind knows how); Instant never opens a permission dialog.
                if (result.permissions.isNotEmpty()) return emptyList()
                continue
            }
            val people = result.payload?.optJSONArray("contacts") ?: continue
            for (i in 0 until people.length()) {
                val person = people.optJSONObject(i) ?: continue
                val numbers = person.optJSONArray("phones")?.let { list -> (0 until list.length()).map { list.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
                val who = person.optString("name")
                if (who.isNotBlank()) found.putIfAbsent(who, InstantContact(who, numbers))
            }
            // The owner's own word for them wins when it matches exactly ("Mam").
            found.values.filter { it.name.equals(query, ignoreCase = true) }.takeIf { it.size == 1 }?.let { return it }
        }
        return found.values.toList()
    }

    override fun dial(number: String): InstantMove {
        val clean = number.filter { it.isDigit() || it == '+' }
        if (clean.length < 3) return InstantMove(false, false, "no usable number")
        if (current() == null) return InstantMove(false, false, "the screen couldn't be read")
        return move(env.act("phone.launch_intent", JSONObject().put("uri", "tel:$clean"), "Instant: open the dialer"))
    }

    override fun confirmWindow(text: String, ms: Long): Boolean = confirm(text, ms)

    override fun timer(seconds: Int) = direct("timer", JSONObject().put("seconds", seconds))
    override fun alarm(hour: Int, minute: Int) = direct("alarm", JSONObject().put("hour", hour).put("minute", minute))
    override fun flashlight(on: Boolean) = direct("flashlight", JSONObject().put("on", on))
    override fun volume(up: Boolean) = direct("volume", JSONObject().put("direction", if (up) "up" else "down"))
    override fun media(action: String) = direct("media", JSONObject().put("action", action))
    override fun stopped(): Boolean = cancelled()

    private fun direct(tool: String, params: JSONObject): InstantMove {
        val result = device.direct(tool, params)
        return InstantMove(result.ok, result.ok, result.error ?: "done")
    }

    /**
     * The screen as read for this move (alpha.78): the reading Instant already made when nothing has moved since, else a
     * new one. One read per move instead of two; any move clears it, so the next one reads again.
     */
    private fun current(): AgentPageCard? = page ?: fresh()

    private fun fresh(): AgentPageCard? {
        val card = runCatching { env.observe(goal).page }.getOrNull() ?: return null
        page = card
        controls = env.allControls().ifEmpty { card.controls }
        return card
    }

    private fun move(envelope: AgentActionEnvelope): InstantMove {
        page = null
        val ok = envelope.androidExecutionOk && envelope.executorReportedOk
        return InstantMove(ok, envelope.pageChanged, envelope.safeMessage ?: envelope.errorClass.name.lowercase().replace('_', ' '))
    }

    companion object {
        /** What the owner calls family, and what their contacts are often called (English and Dutch). */
        private val FAMILY = mapOf(
            "mom" to listOf("mam", "mama", "mum", "mother", "moeder", "ma"), "mum" to listOf("mom", "mam", "mama", "mother"),
            "mam" to listOf("mama", "mom", "moeder", "ma"), "mama" to listOf("mam", "mom", "moeder"),
            "dad" to listOf("pap", "papa", "father", "vader", "pa"), "pap" to listOf("papa", "dad", "vader", "pa"),
            "papa" to listOf("pap", "dad", "vader"),
        )
    }
}
