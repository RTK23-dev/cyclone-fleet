package com.cyclone.mobile.voice

/**
 * The turn-taking engine (plan 24 §5.2): the whole voice conversation as a pure state machine. The session feeds it
 * events (a tap, the end of speech, a transcript, the model's reading, the end of a spoken line, the task ending) and
 * carries out the effects it returns. There are no Android types here, so every path is a JVM test.
 *
 * Voice never acts (plan 32): its only ways to change anything are [VoiceEffect.Submit] (a new Ask, exactly as if
 * typed) and [VoiceEffect.Send] (a Task Kit command to the running task).
 */
enum class VoicePhase { IDLE, LISTENING, TRANSCRIBING, UNDERSTANDING, ACKING, WORKING, ASKING, READBACK, DONE, CLOSED }

enum class Earcon { LISTEN, CLOSE_SOFT, DONE, FAILED, NEEDS_YOU }

/** What happens when a spoken line ends. */
enum class AfterSpeech { LISTEN, WORK, CLOSE }

/** A Task Kit command, as voice may send it. The session maps these to `TaskCommand`s. */
sealed interface VoiceAnswer {
    data class Reply(val text: String) : VoiceAnswer
    data class Fill(val values: Map<String, String>) : VoiceAnswer
    /** Approves the moment [momentId] only: the session sends it only while that exact moment is still open. */
    data class Approve(val momentId: String) : VoiceAnswer
    data object Decline : VoiceAnswer
    data object Stop : VoiceAnswer
}

enum class VoiceFailure {
    NO_KEY, OFFLINE, NOT_HEARD, MIC_BUSY, NO_MIC,
    /** The recording ran but Android gave it only silence (alpha.72): the session retries with the system recognizer. */
    MIC_SILENCED,
}

enum class TaskOutcome { DONE, FAILED, STOPPED }

/** An open Owner Moment, as voice sees it. */
data class VoiceMoment(
    val id: String,
    val kind: Kind,
    /** What the task asks, already redacted. */
    val text: String,
    val choices: List<String> = emptyList(),
    val fields: List<Field> = emptyList(),
    /** SEND: who the message goes to. */
    val recipient: String = "",
    /** SEND: the exact message, as it will be sent. */
    val message: String = "",
) {
    enum class Kind { QUESTION, VALUES, SEND, APPROVAL, SECRET, HANDOVER }
    data class Field(val label: String, val choices: List<String> = emptyList())
}

sealed interface VoiceEvent {
    /** The orb was tapped (or held and released). */
    data object Tap : VoiceEvent
    /** The owner spoke and stopped; the clip is ready to transcribe. */
    data object Heard : VoiceEvent
    /** Silence, noise or a blip: no call is made. */
    data object NothingHeard : VoiceEvent
    data class Transcript(val text: String) : VoiceEvent
    data class Understood(val understanding: Understanding) : VoiceEvent
    data class Failed(val failure: VoiceFailure) : VoiceEvent
    /** A spoken line finished (or was cut off by the owner). */
    data object SpeechEnded : VoiceEvent
    /** A task is running (started by voice or not). */
    data object TaskStarted : VoiceEvent
    data class TaskEnded(val outcome: TaskOutcome, val summary: String = "") : VoiceEvent
    /** Sixty seconds of work without news. */
    data object StillWorking : VoiceEvent
    data class MomentOpened(val moment: VoiceMoment) : VoiceEvent
    data object MomentClosed : VoiceEvent
    /** A message arrived and the owner asked to hear it (plan 32 D3). Said only when the turn is free. */
    data class Announce(val offer: VoiceOffer) : VoiceEvent
    /** The minute to answer an announcement is over. */
    data class OfferExpired(val id: String) : VoiceEvent
    /** The big Stop button: ends the voice turn and the task. */
    data object Stop : VoiceEvent
    /**
     * Plan 42 (Live): the modes router finished a quick request. [say] is null for a silent success (a sound instead);
     * [promoted] means it went on as a Flash or Mind mission.
     */
    data class QuickDone(val ok: Boolean, val say: String?, val promoted: Boolean) : VoiceEvent
    /** "Not now" while Cyclone asks: declines the open question and closes. */
    data object NotNow : VoiceEvent
}

sealed interface VoiceEffect {
    data class Play(val earcon: Earcon) : VoiceEffect
    /** Open the microphone (the only way it opens: a tap, or right after Cyclone asks something). */
    data object Listen : VoiceEffect
    /** Close the microphone and drop the clip. */
    data object StopListening : VoiceEffect
    data object Transcribe : VoiceEffect
    data class Understand(val transcript: String, val context: VoiceContext) : VoiceEffect
    data class Say(val line: String, val then: AfterSpeech) : VoiceEffect
    data object StopSpeaking : VoiceEffect
    /** Start a new Ask with [goal], exactly as if typed. */
    data class Submit(val goal: String) : VoiceEffect
    /** Plan 42 (Live): the same, for a quick command; the session reports back with [VoiceEvent.QuickDone]. */
    data class Quick(val goal: String) : VoiceEffect
    /** Plan 42 (Live): open the microphone again for up to [ms] of silence, for a follow-up or the rest of a sentence. */
    data class KeepListening(val ms: Int) : VoiceEffect
    data class Send(val answer: VoiceAnswer) : VoiceEffect
}

data class VoiceTurn(
    val phase: VoicePhase = VoicePhase.IDLE,
    val taskLive: Boolean = false,
    /** What the owner said last (user caption). */
    val heard: String = "",
    /** What Cyclone says or said last (Cyclone caption). */
    val said: String = "",
    val speaking: Boolean = false,
    val after: AfterSpeech = AfterSpeech.CLOSE,
    val unclearCount: Int = 0,
    val followUp: VoiceContext.FollowUp? = null,
    val moment: VoiceMoment? = null,
    /** The moments already announced, so one moment is spoken once. */
    val announced: Set<String> = emptySet(),
    val stillSaid: Boolean = false,
    val stopping: Boolean = false,
    /** A task ended while the owner was talking; said as soon as the turn is free. */
    val pendingEnd: Pair<TaskOutcome, String>? = null,
    val recentGoals: List<String> = emptyList(),
    val language: String = "auto",
    /** VALUES by voice: the answers so far, one field at a time. */
    val filled: Map<String, String> = emptyMap(),
    /** An announced message a tap can still answer ("yes", "tell her …", "no"). */
    val offer: VoiceOffer? = null,
    /** The SEND moment whose readback was heard to the end: only then does a yes approve it. */
    val readbackHeard: String? = null,
    /** Plan 42 (Live): quick commands go straight to the modes router (off when Speed is Always Mind). */
    val quickCommands: Boolean = true,
    /** Plan 42 (Live): how long the mic stays open after a quick action; 0 closes it. */
    val keepListeningMs: Int = 8_000,
    /** A quick command is with the router now. */
    val quickLive: Boolean = false,
    /** The mic reopened after a quick action: what the owner says next may finish the sentence ("… and take a selfie"). */
    val keptOpen: Boolean = false,
) {
    data class Step(val turn: VoiceTurn, val effects: List<VoiceEffect>)

    /**
     * AI mode is up (the panel shows) in these phases, and while a quick command runs (alpha.78: voice mode keeps the
     * screen, with its big Stop in reach); otherwise only the button, with a ring while working.
     */
    val panelOpen: Boolean get() = quickLive || phase in setOf(VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING,
        VoicePhase.ACKING, VoicePhase.ASKING, VoicePhase.READBACK, VoicePhase.DONE)

    /** The screen dims only while a request is live (plan 32). */
    val dimmed: Boolean get() = phase in setOf(VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING, VoicePhase.ASKING,
        VoicePhase.READBACK)

    /** Cyclone waits for an answer: the open moment, or its own follow-up question. */
    val answering: Boolean get() = followUp != null || moment?.kind in VOICE_KINDS || offer != null

    fun on(event: VoiceEvent): Step = when (event) {
        VoiceEvent.Tap -> tap()
        VoiceEvent.Heard -> if (phase == VoicePhase.LISTENING) step(copy(phase = VoicePhase.TRANSCRIBING), VoiceEffect.Transcribe) else same()
        VoiceEvent.NothingHeard -> if (phase == VoicePhase.LISTENING) {
            // The window after a quick action closes quietly: nothing was wanted.
            rest(if (keptOpen) emptyList() else listOf(VoiceEffect.Play(Earcon.CLOSE_SOFT)))
        } else same()
        is VoiceEvent.Transcript -> if (phase == VoicePhase.TRANSCRIBING) transcript(event.text) else same()
        is VoiceEvent.Understood -> if (phase == VoicePhase.UNDERSTANDING) understood(event.understanding) else same()
        is VoiceEvent.Failed -> if (phase in LISTEN_PHASES) say(failure(event.failure), AfterSpeech.CLOSE, VoicePhase.DONE) else same()
        VoiceEvent.SpeechEnded -> speechEnded()
        VoiceEvent.TaskStarted -> step(copy(taskLive = true, stopping = false, phase = if (phase in setOf(VoicePhase.IDLE, VoicePhase.CLOSED)) VoicePhase.WORKING else phase))
        is VoiceEvent.TaskEnded -> taskEnded(event.outcome, event.summary)
        VoiceEvent.StillWorking -> if (phase == VoicePhase.WORKING && taskLive && !stillSaid && !speaking)
            say(VoiceCopy.STILL_WORKING, AfterSpeech.WORK, VoicePhase.WORKING).let { Step(it.turn.copy(stillSaid = true), it.effects) } else same()
        is VoiceEvent.MomentOpened -> momentOpened(event.moment)
        VoiceEvent.MomentClosed -> step(copy(moment = null, filled = emptyMap(), phase = if (phase in setOf(VoicePhase.ASKING, VoicePhase.READBACK) && !speaking) restPhase() else phase))
        VoiceEvent.Stop -> stop()
        VoiceEvent.NotNow -> notNow()
        is VoiceEvent.Announce -> announceMessage(event.offer)
        is VoiceEvent.OfferExpired -> if (offer?.id == event.id) step(copy(offer = null)) else same()
        is VoiceEvent.QuickDone -> quickDone(event)
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    private fun tap(): Step = when {
        phase in setOf(VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING) -> same()
        // While listening a tap means "I'm finished": the capture ends and delivers what it has.
        phase == VoicePhase.LISTENING -> same()
        // Barge-in: a tap while Cyclone speaks stops the line and listens, keeping any open question.
        speaking -> step(copy(phase = VoicePhase.LISTENING, speaking = false), VoiceEffect.StopSpeaking, VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen)
        else -> step(copy(phase = VoicePhase.LISTENING, heard = "", said = if (answering) said else ""), VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen)
    }

    private fun transcript(text: String): Step {
        // A readback is answered by an explicit yes or no without a model: faster, and a yes is never guessed.
        val open = moment
        if (open?.kind == VoiceMoment.Kind.SEND && followUp == null) {
            when (VoiceRules.yesNo(text)) {
                true -> return copy(heard = text.trim()).confirm(open)
                false -> return copy(heard = text.trim()).declineMoment(text.trim())
                null -> Unit
            }
        }
        // An announcement is answered by yes, "reply" or no without a model.
        val waiting = offer
        if (waiting != null && open == null && followUp == null) {
            val yes = VoiceRules.yesNo(text)
            if (yes == true || VoiceAnnounce.replyWord(text)) return copy(heard = text.trim()).takeOffer(waiting, null)
            if (yes == false) return copy(offer = null).sayClosing(VoiceCopy.OKAY, text.trim())
        }
        return screenTranscript(text)
    }

    private fun screenTranscript(text: String): Step = when (val screen = VoiceRules.screen(text, answering)) {
        VoiceRules.Screen.Empty, VoiceRules.Screen.Filler -> rest(listOf(VoiceEffect.Play(Earcon.CLOSE_SOFT)))
        is VoiceRules.Screen.Cancel -> if (offer != null && moment == null) copy(offer = null).sayClosing(VoiceCopy.OKAY, text.trim())
        else if (screen.stop && taskLive) {
            val s = say(VoiceCopy.STOPPING, AfterSpeech.CLOSE, VoicePhase.DONE)
            Step(s.turn.copy(stopping = true, heard = text.trim()), listOf(VoiceEffect.Send(VoiceAnswer.Stop)) + s.effects)
        } else {
            if (moment != null && moment.kind in VOICE_KINDS) declineMoment(text.trim())
            else sayClosing(VoiceCopy.OKAY, text.trim())
        }
        is VoiceRules.Screen.Pass -> {
            val said = if (keptOpen) VoiceQuick.continuation(screen.text) else screen.text
            val understanding = copy(phase = VoicePhase.UNDERSTANDING, heard = said, keptOpen = false)
            // Instant commands (a timer, an alarm) need no model when nothing is being asked: the confirmation starts now.
            val instant = if (!answering && followUp == null) VoiceIntents.parse(said) else null
            when {
                instant != null -> understanding.understood(instant)
                // Plan 42 (Live): a quick command goes to the router at once: no model reading, no "On it.".
                quickCommands && !answering && followUp == null && !taskLive && !quickLive &&
                    com.cyclone.mobile.mind.modes.InstantGrammar.quick(said) ->
                    step(understanding.copy(phase = VoicePhase.WORKING, quickLive = true, unclearCount = 0,
                        recentGoals = (recentGoals + said).takeLast(3)), VoiceEffect.Quick(said))
                else -> step(understanding, VoiceEffect.Understand(said, VoiceContext(followUp, openAsk(), recentGoals, taskLive, language)))
            }
        }
    }

    private fun understood(u: Understanding): Step {
        val open = moment?.takeIf { it.kind in VOICE_KINDS }
        val waiting = offer
        if (open == null && waiting != null) return copy(offer = null).offerAnswered(waiting, u)
        return when (u.kind) {
            VoiceKind.NONE -> rest(listOf(VoiceEffect.Play(Earcon.CLOSE_SOFT)))
            VoiceKind.CANCEL -> if (open != null) declineMoment(heard) else sayClosing(VoiceCopy.OKAY)
            VoiceKind.UNCLEAR -> unclear(u)
            VoiceKind.TASK, VoiceKind.REPLY -> when {
                // A new request while a question is open is most likely the answer said differently.
                open != null -> answer(open, u.goal)
                taskLive -> sayClosing(VoiceCopy.BUSY)
                else -> {
                    val s = say(VoiceCopy.ack(u.ack), AfterSpeech.WORK, VoicePhase.ACKING)
                    Step(s.turn.copy(taskLive = true, stillSaid = false, stopping = false, unclearCount = 0, followUp = null,
                        recentGoals = (recentGoals + u.goal).takeLast(3)), listOf(VoiceEffect.Submit(u.goal)) + s.effects)
                }
            }
            VoiceKind.ANSWER -> if (open != null) answer(open, u.goal) else if (!taskLive && u.goal.isNotBlank())
                understood(u.copy(kind = VoiceKind.TASK)) else sayClosing(VoiceCopy.NOTHING_OPEN)
            VoiceKind.CONFIRM -> if (open != null) confirm(open) else sayClosing(VoiceCopy.NOTHING_OPEN)
            VoiceKind.DECLINE -> if (open != null) declineMoment(heard) else sayClosing(VoiceCopy.OKAY)
        }
    }

    /**
     * Plan 42 (Live): a quick command came back. Done: a sound (or the short line when silent success is off) and the
     * mic stays open. Handed up: it is a task now, confirmed like one. Failed: said, then closed.
     */
    private fun quickDone(event: VoiceEvent.QuickDone): Step {
        if (!quickLive) return same()
        val back = copy(quickLive = false)
        if (event.promoted) {
            val s = back.say(VoiceCopy.DEFAULT_ACK, AfterSpeech.WORK, VoicePhase.ACKING)
            return Step(s.turn.copy(taskLive = true, stillSaid = false, stopping = false), s.effects)
        }
        if (!event.ok) return back.sayClosing(VoiceCopy.failed(event.say))
        val line = event.say
        if (line != null) {
            val s = back.say(line, if (keepListeningMs > 0) AfterSpeech.LISTEN else AfterSpeech.CLOSE, VoicePhase.DONE)
            return Step(s.turn.copy(keptOpen = keepListeningMs > 0), s.effects)
        }
        if (keepListeningMs <= 0) return Step(back.copy(phase = back.restPhase()), listOf(VoiceEffect.Play(Earcon.DONE)))
        return Step(back.copy(phase = VoicePhase.LISTENING, keptOpen = true, heard = "", said = ""),
            listOf(VoiceEffect.Play(Earcon.DONE), VoiceEffect.KeepListening(keepListeningMs)))
    }

    /** What the owner said after an announcement: the reply's content, a yes or no, or a new request of their own. */
    private fun offerAnswered(waiting: VoiceOffer, u: Understanding): Step = when (u.kind) {
        VoiceKind.ANSWER -> takeOffer(waiting, u.goal)
        VoiceKind.CONFIRM -> takeOffer(waiting, null)
        VoiceKind.DECLINE, VoiceKind.CANCEL -> sayClosing(VoiceCopy.OKAY)
        else -> understood(u)
    }

    /** Starts the reply to an announced message; the Mind asks what to say unless [answer] already says it. */
    private fun takeOffer(waiting: VoiceOffer, answer: String?): Step {
        val goal = waiting.replyGoal ?: return copy(offer = null).sayClosing(VoiceCopy.OKAY)
        if (taskLive) return copy(offer = null).sayClosing(VoiceCopy.BUSY)
        val full = if (answer.isNullOrBlank()) goal else VoiceAnnounce.replyWith(waiting, answer)
        val s = say(VoiceCopy.ack("Replying to ${waiting.sender}."), AfterSpeech.WORK, VoicePhase.ACKING)
        return Step(s.turn.copy(offer = null, taskLive = true, stillSaid = false, stopping = false, unclearCount = 0, followUp = null,
            recentGoals = (recentGoals + goal).takeLast(3)), listOf(VoiceEffect.Submit(full)) + s.effects)
    }

    /** Says an announced message when nothing else is going on; otherwise it stays on screen as a notification. */
    private fun announceMessage(o: VoiceOffer): Step {
        if (taskLive || moment != null || speaking || followUp != null || phase !in setOf(VoicePhase.IDLE, VoicePhase.CLOSED)) return same()
        val s = say(o.line, AfterSpeech.CLOSE, VoicePhase.DONE)
        return Step(s.turn.copy(offer = o.takeIf { it.replyGoal != null }, heard = "", unclearCount = 0), listOf(VoiceEffect.Play(Earcon.NEEDS_YOU)) + s.effects)
    }

    private fun unclear(u: Understanding): Step {
        if (unclearCount >= 1) return sayClosing(VoiceCopy.UNCLEAR_AGAIN)
        val question = VoiceCopy.question(u.missing)
        // Keep the owner's first words: the answer to the question completes them.
        val earlier = followUp?.earlier ?: heard
        val s = say(question, AfterSpeech.LISTEN, VoicePhase.ASKING)
        return Step(s.turn.copy(unclearCount = unclearCount + 1, followUp = VoiceContext.FollowUp(earlier, question)), s.effects)
    }

    private fun speechEnded(): Step {
        if (!speaking) return same()
        val readback = moment?.takeIf { phase == VoicePhase.READBACK && it.kind == VoiceMoment.Kind.SEND }?.id
        val quiet = copy(speaking = false, readbackHeard = readback ?: readbackHeard)
        return when (after) {
            AfterSpeech.LISTEN -> Step(quiet.copy(phase = VoicePhase.LISTENING), listOf(VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen))
            AfterSpeech.WORK -> quiet.freeOrPending(VoicePhase.WORKING)
            AfterSpeech.CLOSE -> quiet.freeOrPending(null)
        }
    }

    private fun taskEnded(outcome: TaskOutcome, summary: String): Step {
        val ended = copy(taskLive = false, stillSaid = false, moment = null, followUp = if (moment != null) null else followUp)
        // Stopped by the owner: they already know.
        if (outcome == TaskOutcome.STOPPED && stopping) return Step(ended.copy(stopping = false), emptyList())
        return if (phase in BUSY_PHASES || speaking) Step(ended.copy(pendingEnd = outcome to summary), emptyList())
        else ended.announce(outcome, summary)
    }

    private fun momentOpened(m: VoiceMoment): Step {
        if (moment?.id == m.id) return same()
        val next = copy(moment = m, filled = emptyMap(), offer = null)
        // The owner is talking or Cyclone is speaking: the moment is said as soon as the turn is free.
        if (phase in BUSY_PHASES || speaking) return Step(next, emptyList())
        return next.speakMoment(m)
    }

    private fun stop(): Step {
        val effects = buildList {
            if (phase == VoicePhase.LISTENING) add(VoiceEffect.StopListening)
            if (speaking) add(VoiceEffect.StopSpeaking)
            if (taskLive) add(VoiceEffect.Send(VoiceAnswer.Stop))
        }
        return Step(copy(phase = VoicePhase.CLOSED, speaking = false, stopping = taskLive, followUp = null, unclearCount = 0, offer = null,
            quickLive = false, keptOpen = false,
            pendingEnd = null, said = if (taskLive) VoiceCopy.STOPPED else said), effects)
    }

    private fun notNow(): Step {
        val open = moment
        val effects = buildList {
            if (phase == VoicePhase.LISTENING) add(VoiceEffect.StopListening)
            if (speaking) add(VoiceEffect.StopSpeaking)
            if (open != null && open.kind in DECLINABLE) add(VoiceEffect.Send(VoiceAnswer.Decline))
        }
        return Step(copy(phase = restPhase(), speaking = false, followUp = null, unclearCount = 0, moment = if (open?.kind in DECLINABLE) null else open), effects)
    }

    // ---- moments (extended by plan 32 D2) ---------------------------------------------------------------------------

    /** Speaks [m]: kinds voice can answer ask and listen; everything else needs the owner on screen. */
    private fun speakMoment(m: VoiceMoment): Step {
        val line = VoiceMoments.prompt(m)
        val listens = m.kind in VOICE_KINDS
        val s = say(line, if (listens) AfterSpeech.LISTEN else AfterSpeech.WORK,
            if (m.kind == VoiceMoment.Kind.SEND) VoicePhase.READBACK else if (listens) VoicePhase.ASKING else VoicePhase.DONE)
        return Step(s.turn.copy(unclearCount = 0, followUp = null, announced = (announced + m.id).toList().takeLast(20).toSet()),
            listOf(VoiceEffect.Play(Earcon.NEEDS_YOU)) + s.effects)
    }

    private fun answer(open: VoiceMoment, text: String): Step = when (open.kind) {
        VoiceMoment.Kind.QUESTION -> sendAnswer(VoiceAnswer.Reply(text), VoiceCopy.OKAY)
        VoiceMoment.Kind.VALUES -> fillNext(open, text)
        // "Change it to …": the Mind edits the draft and opens a new readback.
        // The model called a plain yes or no an answer: treat it as what it is, never as an edit.
        VoiceMoment.Kind.SEND -> when (VoiceRules.yesNo(text)) {
            true -> confirm(open)
            false -> declineMoment(text)
            null -> sendAnswer(VoiceAnswer.Reply(VoiceMoments.edit(text)), VoiceMoments.EDITING)
        }
        else -> sayClosing(VoiceCopy.NEEDS_SCREEN)
    }

    /** VALUES: keep this answer for the field being asked, then ask the next one, or send them all. */
    private fun fillNext(open: VoiceMoment, text: String): Step {
        val field = open.fields.getOrNull(filled.size) ?: return sayClosing(VoiceCopy.NEEDS_SCREEN)
        val next = filled + (field.label to text.trim())
        val following = open.fields.getOrNull(next.size)
        if (following == null) return sendAnswer(VoiceAnswer.Fill(next), VoiceCopy.OKAY).let { Step(it.turn.copy(filled = emptyMap()), it.effects) }
        val s = say(VoiceMoments.fieldQuestion(following), AfterSpeech.LISTEN, VoicePhase.ASKING)
        return Step(s.turn.copy(filled = next, unclearCount = 0, followUp = null), s.effects)
    }

    private fun confirm(open: VoiceMoment): Step = when (open.kind) {
        // Only send is approved by voice, and only after its verbatim readback was heard (plan 32).
        // A readback cut short by a tap is read again in full before a yes counts.
        VoiceMoment.Kind.SEND -> if (readbackHeard == open.id) sendAnswer(VoiceAnswer.Approve(open.id), VoiceMoments.SENDING)
            else say(VoiceMoments.readback(open), AfterSpeech.LISTEN, VoicePhase.READBACK)
        VoiceMoment.Kind.QUESTION -> sendAnswer(VoiceAnswer.Reply(heard.ifBlank { "Yes" }), VoiceCopy.OKAY)
        VoiceMoment.Kind.VALUES -> fillNext(open, heard)
        else -> sayClosing(VoiceCopy.NEEDS_SCREEN)
    }

    private fun declineMoment(said: String): Step {
        val open = moment ?: return sayClosing(VoiceCopy.OKAY, said)
        return if (open.kind in DECLINABLE) sendAnswer(VoiceAnswer.Decline, if (open.kind == VoiceMoment.Kind.SEND) VoiceMoments.NOT_SENT else VoiceCopy.OKAY)
        else sayClosing(VoiceCopy.OKAY, said)
    }

    private fun sendAnswer(answer: VoiceAnswer, line: String): Step {
        val s = say(line, AfterSpeech.WORK, VoicePhase.ACKING)
        return Step(s.turn.copy(moment = null, followUp = null, unclearCount = 0, filled = emptyMap()), listOf(VoiceEffect.Send(answer)) + s.effects)
    }

    private fun openAsk(): VoiceContext.OpenAsk? {
        val open = moment?.takeIf { it.kind in VOICE_KINDS } ?: return offer?.let(VoiceAnnounce::openAsk)
        return when (open.kind) {
            VoiceMoment.Kind.VALUES -> open.fields.getOrNull(filled.size)?.let { f -> VoiceContext.OpenAsk("details", VoiceMoments.fieldQuestion(f), f.choices) }
            VoiceMoment.Kind.SEND -> VoiceContext.OpenAsk("readback", VoiceMoments.prompt(open))
            else -> VoiceContext.OpenAsk(open.kind.name.lowercase(), VoiceMoments.prompt(open), open.choices)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private fun announce(outcome: TaskOutcome, summary: String): Step {
        val (earcon, line) = when (outcome) {
            TaskOutcome.DONE -> Earcon.DONE to VoiceCopy.done(summary)
            TaskOutcome.FAILED -> Earcon.FAILED to VoiceCopy.failed(summary)
            TaskOutcome.STOPPED -> Earcon.FAILED to VoiceCopy.STOPPED
        }
        val s = say(line, AfterSpeech.CLOSE, VoicePhase.DONE)
        return Step(s.turn.copy(pendingEnd = null), listOf(VoiceEffect.Play(earcon)) + s.effects)
    }

    /** The turn is free: say what is waiting (a task that ended, a moment not yet spoken), or settle in [phase]. */
    private fun freeOrPending(phase: VoicePhase?): Step {
        pendingEnd?.let { (outcome, summary) -> return announce(outcome, summary) }
        moment?.takeIf { it.id !in announced }?.let { return speakMoment(it) }
        return step(copy(phase = phase ?: restPhase(), followUp = if (phase == null) null else followUp,
            unclearCount = if (phase == null) 0 else unclearCount))
    }

    private fun say(line: String, then: AfterSpeech, phase: VoicePhase): Step {
        val spoken = VoiceRedaction.spoken(line)
        return step(copy(phase = phase, said = spoken, speaking = true, after = then), VoiceEffect.Say(spoken, then))
    }

    private fun sayClosing(line: String, heardText: String = heard): Step =
        say(line, AfterSpeech.CLOSE, VoicePhase.DONE).let { Step(it.turn.copy(heard = heardText, followUp = null, unclearCount = 0, keptOpen = false), it.effects) }

    private fun rest(effects: List<VoiceEffect>): Step =
        Step(copy(phase = restPhase(), followUp = null, unclearCount = 0, speaking = false, keptOpen = false), effects)

    private fun restPhase(): VoicePhase = if (taskLive) VoicePhase.WORKING else VoicePhase.CLOSED

    private fun failure(f: VoiceFailure): String = when (f) {
        VoiceFailure.NO_KEY -> VoiceCopy.NO_KEY
        VoiceFailure.OFFLINE -> VoiceCopy.OFFLINE
        VoiceFailure.NOT_HEARD -> VoiceCopy.NOT_HEARD
        VoiceFailure.MIC_BUSY -> VoiceCopy.MIC_BUSY
        VoiceFailure.NO_MIC -> VoiceCopy.NO_MIC
        VoiceFailure.MIC_SILENCED -> VoiceCopy.MIC_SILENCED
    }

    private fun same() = Step(this, emptyList())
    private fun step(turn: VoiceTurn, vararg effects: VoiceEffect) = Step(turn, effects.toList())

    companion object {
        /** Moments the owner can answer by voice. Everything else waits for them on screen. */
        val VOICE_KINDS: Set<VoiceMoment.Kind> get() = VoiceMoments.VOICE_KINDS
        /** "Not now" and "no" decline these; approvals on screen are the owner's to answer there. */
        private val DECLINABLE: Set<VoiceMoment.Kind> get() = VoiceMoments.VOICE_KINDS
        private val LISTEN_PHASES = setOf(VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING)
        private val BUSY_PHASES = setOf(VoicePhase.LISTENING, VoicePhase.TRANSCRIBING, VoicePhase.UNDERSTANDING)
    }
}
