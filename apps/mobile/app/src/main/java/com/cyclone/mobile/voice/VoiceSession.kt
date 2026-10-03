package com.cyclone.mobile.voice

import android.content.Context
import android.os.SystemClock
import com.cyclone.mobile.ai.OpenRouterSecretStore
import com.cyclone.mobile.owner.OwnerMomentsRuntime
import com.cyclone.mobile.runtime.background.TaskPhase
import com.cyclone.mobile.task.TaskCommand
import com.cyclone.mobile.task.TaskCommands
import com.cyclone.mobile.ui.overlay.OverlayChromeRuntime
import com.cyclone.mobile.ui.overlay.glass.VoicePress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * One Drive session (plan 24 §6): carries out what [VoiceTurn] decides. It records, transcribes, understands and
 * speaks; it watches the running task and its Owner Moments; and it hands work to Cyclone in exactly two ways:
 * [OverlayChromeRuntime.submitRequest] for a new request and [TaskCommands.send] for everything else (plan 32:
 * voice never acts).
 *
 * All state changes happen on the main thread, one event at a time.
 */
class VoiceSession(context: Context, sink: VoiceRunSink = VoiceRunSink.NONE) {
    private val app = context.applicationContext
    /** Alpha.78: every voice run is logged, stage by stage with timings, to Cyclone's run history. */
    private val runLog = VoiceRunLog(sink) { SystemClock.elapsedRealtime() }
    private val ears = VoiceEars()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speech = SpeechOut(app)
    private val capture = VoiceCapture(app)
    private val onDevice = OnDeviceStt(app)

    private val _turn = MutableStateFlow(VoiceTurn(language = DriverMode.load(app).language))
    val turn: StateFlow<VoiceTurn> = _turn
    private val micLevel = MutableStateFlow(0f)
    private val _level = MutableStateFlow(0f)
    /** The orb's level: the owner's voice while listening, Cyclone's while speaking. */
    val level: StateFlow<Float> = _level

    private val _timings = MutableStateFlow(VoiceTimings())
    /** How fast the last requests were, for Settings → Voice. */
    val timings: StateFlow<VoiceTimings> = _timings

    private var earconJob: Job? = null
    private var listenJob: Job? = null
    private var speakJob: Job? = null
    private var workJob: Job? = null
    private var stillJob: Job? = null
    private val calls = mutableListOf<Call>()
    private var clip: ShortArray? = null
    private var heardText: String? = null
    private var stoppedTalkingAt = 0L
    private var awaitingAckSound = false
    private var liveTaskId: String? = null
    private var ear = VoiceEars.Ear.RECORDER

    init {
        speech.prepare(DriverMode.load(app).language)
        scope.launch { combine(micLevel, speech.level) { mic, out -> maxOf(mic, out) }.collect { _level.value = it } }
        scope.launch { OwnerMomentsRuntime.task.collect(::onTask) }
        scope.launch { OwnerMomentsRuntime.moments().collect { moment ->
            val voice = VoiceMomentSource.of(moment)
            if (voice != null) dispatch(VoiceEvent.MomentOpened(voice)) else if (_turn.value.moment != null) dispatch(VoiceEvent.MomentClosed)
        } }
        // Messages the owner asked to hear (plan 32 D3): said when the turn is free; a tap within a minute answers.
        scope.launch { DriveAnnouncements.incoming.collect { message ->
            val offer = VoiceAnnounce.offer(message) ?: return@collect
            dispatch(VoiceEvent.Announce(offer))
            if (_turn.value.offer?.id == offer.id) scope.launch {
                delay(VoiceAnnounce.OFFER_MS)
                dispatch(VoiceEvent.OfferExpired(offer.id))
            }
        } }
        // The live model lists: fetched once per session in the background, so the first request does not wait.
        // Then the stock lines ("On it.", "Okay.", "Done.") are made once for this voice, so they play at once.
        scope.launch(Dispatchers.IO) {
            runCatching {
                val key = OpenRouterSecretStore.read(app)
                VoiceCatalog.refresh(key)
                val choice = VoiceCatalog.choice(DriverMode.settings.value)
                if (key.isNotBlank() && choice.tts != null) speech.prewarm(OpenRouterVoice(key), choice.tts, choice.voice)
            }
        }
    }

    private var lastPressAt = 0L
    private var listeningSince = 0L

    /**
     * The orb was tapped. [VoicePress] keeps the same guards as the voice button: a bounce or quick second touch is
     * one press, and a tap in the first moments of listening does not cut the owner off.
     */
    fun tap() {
        val now = SystemClock.uptimeMillis()
        val listening = _turn.value.phase == VoicePhase.LISTENING
        when (VoicePress.down(now, listening, listeningSince, lastPressAt)) {
            VoicePress.Action.NONE -> return
            VoicePress.Action.STOP -> { lastPressAt = now; capture.finish(); onDevice.finish(); runLog.note("TAP", "Tap: finish listening") }
            VoicePress.Action.START -> { lastPressAt = now; dispatch(VoiceEvent.Tap) }
        }
    }

    fun stop() {
        workJob?.cancel()
        cancelCalls()
        runLog.note("STOP", "Stop pressed", ok = true)
        // Alpha.78: a quick action the owner started by voice stops with the same Stop, at once.
        if (_turn.value.quickLive) OverlayChromeRuntime.stopVoiceRequest()
        dispatch(VoiceEvent.Stop)
        runLog.end("Stopped by the owner")
    }
    fun notNow() = dispatch(VoiceEvent.NotNow)

    fun close() {
        stopListening()
        speech.release()
        cancelCalls()
        scope.cancel()
    }

    // ---- the loop ---------------------------------------------------------------------------------------------------

    private fun dispatch(event: VoiceEvent) {
        val step = _turn.value.on(event)
        val modes = com.cyclone.mobile.mind.modes.CycloneModes.settings(app)
        _turn.value = step.turn.copy(language = DriverMode.settings.value.language,
            quickCommands = modes.speed != com.cyclone.mobile.mind.modes.Speed.MIND, keepListeningMs = modes.keepListeningSeconds * 1_000)
        step.effects.forEach(::run)
        if (step.turn.phase == VoicePhase.WORKING) armStillWorking() else if (!step.turn.taskLive) stillJob?.cancel()
        // The turn is free again: the voice run ends (a task it started keeps its own run log).
        val t = _turn.value
        if (runLog.open && !t.speaking && !t.quickLive && t.phase in setOf(VoicePhase.CLOSED, VoicePhase.IDLE, VoicePhase.WORKING, VoicePhase.DONE)) {
            runLog.end(null)
        }
    }

    private fun run(effect: VoiceEffect) {
        when (effect) {
            is VoiceEffect.Play -> earconJob = scope.launch { speech.earcon(effect.earcon) }
            VoiceEffect.Listen -> listen()
            VoiceEffect.StopListening -> stopListening()
            VoiceEffect.Transcribe -> transcribe()
            is VoiceEffect.Understand -> { runLog.note("UNDERSTAND", "Reading the request with the understanding model"); understand(effect.transcript, effect.context) }
            // A confirmation of new work gets the stock "On it." if it is slow to start (alpha.52).
            is VoiceEffect.Say -> sayLogged(effect.line, quick = VoiceCopy.DEFAULT_ACK.takeIf {
                effect.then == AfterSpeech.WORK && _turn.value.phase == VoicePhase.ACKING && effect.line != it })
            VoiceEffect.StopSpeaking -> { speakJob?.cancel(); speech.stop() }
            is VoiceEffect.Submit -> {
                runLog.note("SUBMIT", "Handed to Cyclone as a task: \"${effect.goal.take(160)}\"")
                OverlayChromeRuntime.submitRequest(effect.goal, driving = true)
            }
            // Plan 42 (Live): the same Ask, with the modes router's result back for a sound, a word or an ack.
            is VoiceEffect.Quick -> {
                runLog.note("QUICK", "Quick command to the modes router: \"${effect.goal.take(160)}\"")
                OverlayChromeRuntime.submitRequest(effect.goal, driving = true) { result ->
                    scope.launch {
                        runLog.note("QUICK_DONE", "${result.mode.name.lowercase()}: " + when {
                            result.promoted -> "handed up to a mission"
                            result.ok -> "done"
                            else -> "failed" + (result.say?.let { " ($it)" } ?: "")
                        }, ok = result.ok, detail = result.steps.joinToString(" · ").ifBlank { null })
                        runLog.outcome(result.say ?: if (result.promoted) "Handed to a ${result.mode.name.lowercase()} mission" else "Done", result.ok)
                        dispatch(VoiceEvent.QuickDone(result.ok, result.say, result.promoted))
                    }
                }
            }
            is VoiceEffect.KeepListening -> listen(effect.ms)
            is VoiceEffect.Send -> { runLog.note("SEND", "Answer to the task: ${effect.answer::class.simpleName}"); send(effect.answer) }
        }
    }

    /**
     * Opens the microphone for one turn. The ear comes from [VoiceEars]: if it can't hear (a silenced or busy
     * recording, a recognizer that can't start), the other ear listens in the same turn, once, and every step is
     * written to the voice run log.
     */
    private fun listen(waitMs: Int? = null) {
        listenJob?.cancel()
        listeningSince = SystemClock.uptimeMillis()
        clip = null
        heardText = null
        val settings = DriverMode.settings.value
        ear = ears.start(settings.onDeviceStt)
        runLog.begin(if (waitMs != null) "the mic stayed open after a quick action" else "a tap", ear.label)
        listenJob = scope.launch {
            // A car kit's microphone link comes up while the earcon plays (plan 32 D3).
            val car = if (settings.bluetoothMic && ear == VoiceEars.Ear.RECORDER) async { capture.routeToCar() } else null
            // The earcon first: the detector learns the room's noise, not our own sound.
            earconJob?.join()
            // Alpha.72: the microphone service must hold the microphone before recording starts. Starting it is
            // asynchronous, and a recording opened before it was silenced by Android while Cyclone is in the
            // background (Drive's usual place, over Maps): the owner spoke and nothing was heard.
            val held = VoiceService.startAndWait(app)
            runLog.note("MIC", if (held) "Microphone service holds the mic" else "Microphone service not up in time; listening anyway", ok = held)
            try {
                var recorderFailure: VoiceFailure? = null
                while (true) {
                    val hearing = if (ear == VoiceEars.Ear.RECORDER) record(settings, waitMs, car?.await())
                        else recognize(settings.language, recorderFailure)
                    when (hearing) {
                        is Hearing.Done -> { dispatch(hearing.event); break }
                        is Hearing.Switch -> {
                            if (hearing.from == VoiceEars.Ear.RECORDER) recorderFailure = hearing.failure
                            runLog.note("EAR_SWITCH", "${hearing.from.label} couldn't listen (${hearing.why}); ${hearing.to.label} listens now",
                                ok = false)
                            if (hearing.to == VoiceEars.Ear.RECORDER) capture.releaseCar()
                            ear = hearing.to
                        }
                    }
                }
            } finally {
                car?.cancel()
                capture.releaseCar()
                micLevel.value = 0f
                VoiceService.stop(app)
            }
        }
    }

    private sealed interface Hearing {
        data class Done(val event: VoiceEvent) : Hearing
        data class Switch(val from: VoiceEars.Ear, val to: VoiceEars.Ear, val why: String, val failure: VoiceFailure) : Hearing
    }

    /** Cyclone's own recording, transcribed afterwards by the owner's model. */
    private suspend fun record(settings: DriverSettings, waitMs: Int?, car: android.media.AudioDeviceInfo?): Hearing {
        val tuning = waitMs?.let { VoiceActivity.Tuning(endSilenceMs = settings.endSilenceMs, noSpeechMs = it) }
            ?: VoiceActivity.Tuning(endSilenceMs = settings.endSilenceMs)
        // Once the owner is really talking, open the connection to OpenRouter so the transcription skips the handshake.
        // A silent or blip-only open never gets here: it still costs no call at all.
        val warm = { scope.launch(Dispatchers.IO) {
            OpenRouterSecretStore.read(app).takeIf { it.isNotBlank() }?.let { runCatching { OpenRouterVoice(it).warm() } }
        }; runLog.note("SPEECH", "Speech started"); Unit }
        return when (val result = capture.record(tuning, car = car, onSpeech = warm) { micLevel.value = it }) {
            is VoiceCapture.Outcome.Clip -> {
                clip = result.samples
                heard()
                runLog.note("CLIP", "Speech ended: ${result.samples.size * 1000L / VoiceActivity.SAMPLE_RATE} ms of audio")
                Hearing.Done(VoiceEvent.Heard)
            }
            VoiceCapture.Outcome.NothingHeard -> {
                runLog.note("NOTHING", "Nothing was said" + (waitMs?.let { " in the $it ms window" } ?: ""))
                Hearing.Done(VoiceEvent.NothingHeard)
            }
            is VoiceCapture.Outcome.Failed -> {
                val to = if (result.failure in RECOGNIZER_TAKES_OVER) ears.recorderDeaf() else null
                if (to != null) Hearing.Switch(VoiceEars.Ear.RECORDER, to, result.failure.name.lowercase(), result.failure)
                else { runLog.failed("The recording failed: ${result.failure.name.lowercase()}"); Hearing.Done(VoiceEvent.Failed(result.failure)) }
            }
        }
    }

    /** Android's speech recognizer: it listens and transcribes in one go, so the transcript skips OpenRouter. */
    private suspend fun recognize(language: String, recorderFailure: VoiceFailure?): Hearing {
        val started = SystemClock.elapsedRealtime()
        return when (val result = onDevice.listen(language) { micLevel.value = it }) {
            is OnDeviceStt.Result.Text -> {
                heardText = result.text
                heard()
                runLog.note("RECOGNIZED", "Recognizer finished", detail = "${SystemClock.elapsedRealtime() - started} ms")
                Hearing.Done(VoiceEvent.Heard)
            }
            OnDeviceStt.Result.NothingHeard -> { runLog.note("NOTHING", "Nothing was said"); Hearing.Done(VoiceEvent.NothingHeard) }
            is OnDeviceStt.Result.Failed -> {
                // The recognizer itself couldn't listen: the recording tries in this turn, and stays the ear after.
                val to = if (result.failure != VoiceFailure.NO_MIC) ears.recognizerBroken() else null
                if (to != null) Hearing.Switch(VoiceEars.Ear.RECOGNIZER, to, "Android ${result.codes}".trim(), result.failure)
                else {
                    // Both ears failed after a silenced recording: say so, instead of a generic "didn't catch that".
                    val failure = if (recorderFailure == VoiceFailure.MIC_SILENCED && result.failure == VoiceFailure.NOT_HEARD) VoiceFailure.MIC_SILENCED
                        else result.failure
                    runLog.failed("Neither ear could listen: ${failure.name.lowercase()}", "Android ${result.codes}".trim())
                    Hearing.Done(VoiceEvent.Failed(failure))
                }
            }
        }
    }

    private fun heard() {
        stoppedTalkingAt = SystemClock.elapsedRealtime()
        awaitingAckSound = true
    }

    private fun stopListening() {
        listenJob?.cancel()
        listenJob = null
        clip = null
        micLevel.value = 0f
        VoiceService.stop(app)
    }

    private fun transcribe() {
        heardText?.let { text -> heardText = null; runLog.heard(text, null); dispatch(VoiceEvent.Transcript(text)); return }
        val samples = clip ?: run {
            runLog.failed("No audio to transcribe")
            return dispatch(VoiceEvent.Failed(VoiceFailure.NOT_HEARD))
        }
        clip = null
        workJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val event = withContext<VoiceEvent>(Dispatchers.IO) {
                try {
                    val key = OpenRouterSecretStore.read(app)
                    if (key.isBlank()) return@withContext VoiceEvent.Failed(VoiceFailure.NO_KEY)
                    val model = model(key) { it.stt } ?: return@withContext VoiceEvent.Failed(VoiceFailure.OFFLINE)
                    VoiceEvent.Transcript(OpenRouterStt(OpenRouterVoice(key), model).transcribe(samples, DriverMode.settings.value.language, ::track))
                } catch (error: VoiceCallException) {
                    // Alpha.78: a network or model error never switches ears; the next tap simply tries again.
                    VoiceEvent.Failed(error.failure)
                } finally {
                    samples.fill(0)
                }
            }
            val ms = SystemClock.elapsedRealtime() - started
            _timings.value = _timings.value.copy(transcribeMs = ms)
            when (event) {
                is VoiceEvent.Transcript -> runLog.heard(event.text, ms)
                is VoiceEvent.Failed -> runLog.failed("Transcription failed: ${event.failure.name.lowercase()}", "$ms ms")
                else -> Unit
            }
            dispatch(event)
        }
    }

    private fun understand(transcript: String, context: VoiceContext) {
        val jev = if (DriverMode.settings.value.jevWatch) watchJev(transcript, context) else null
        workJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            val event = withContext<VoiceEvent>(Dispatchers.IO) {
                try {
                    val key = OpenRouterSecretStore.read(app)
                    if (key.isBlank()) return@withContext VoiceEvent.Failed(VoiceFailure.NO_KEY)
                    val model = model(key) { it.fast } ?: return@withContext VoiceEvent.Failed(VoiceFailure.OFFLINE)
                    VoiceEvent.Understood(OpenRouterVoice(key).understand(model, transcript, context, ::track))
                } catch (error: VoiceCallException) {
                    VoiceEvent.Failed(error.failure)
                }
            }
            val ms = SystemClock.elapsedRealtime() - started
            _timings.value = _timings.value.copy(understandMs = ms)
            when (event) {
                is VoiceEvent.Understood -> runLog.note("UNDERSTOOD", "Understood as ${event.understanding.kind.name.lowercase()}" +
                    event.understanding.goal.takeIf { it.isNotBlank() }?.let { ": \"${it.take(160)}\"" }.orEmpty(), ok = true, detail = "$ms ms")
                is VoiceEvent.Failed -> runLog.failed("Understanding failed: ${event.failure.name.lowercase()}", "$ms ms")
                else -> Unit
            }
            dispatch(event)
            // JEV only watches: its answer is compared with the model's, and never dispatched.
            if (jev != null && event is VoiceEvent.Understood) scope.launch {
                val (decision, ms, error) = withTimeoutOrNull(JEV_WAIT_MS) { jev.await() } ?: Triple(null, JEV_WAIT_MS, "no answer in time")
                JevWatch.record(event.understanding.kind, decision, ms, error)
            }
        }
    }

    /** Asks JEV the same question as the understanding model, in parallel; the answer is only compared. */
    private fun watchJev(transcript: String, context: VoiceContext): Deferred<Triple<JevShadow.Decision?, Long, String?>> =
        scope.async<Triple<JevShadow.Decision?, Long, String?>>(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            try {
                val key = OpenRouterSecretStore.read(app)
                if (key.isBlank()) return@async Triple(null, 0L, "no key")
                val decision = JevShadow.parse(OpenRouterVoice(key).decide(JevShadow.request(transcript, context), ::track))
                Triple(decision, SystemClock.elapsedRealtime() - started, if (decision == null) "unreadable answer" else null)
            } catch (error: VoiceCallException) {
                Triple(null, SystemClock.elapsedRealtime() - started, error.message)
            }
        }

    private fun sayLogged(line: String, quick: String?) {
        runLog.note("SAY", "Cyclone says: \"${line.take(200)}\"")
        say(line, quick)
    }

    private fun say(line: String, quick: String? = null) {
        speakJob?.cancel()
        val job = scope.launch {
            earconJob?.join()
            val key = OpenRouterSecretStore.read(app)
            val settings = DriverMode.settings.value
            val choice = VoiceCatalog.choice(settings)
            val api = key.takeIf { it.isNotBlank() }?.let { OpenRouterVoice(it) }
            val started = SystemClock.elapsedRealtime()
            val spoken = speech.say(line, api, choice.tts, choice.voice, settings.language, quick)
            if (awaitingAckSound) {
                awaitingAckSound = false
                // End of speech to the first sound of the answer: the number plan 24 §7 sets (p50 2.0 s, p90 3.0 s).
                val confirm = started + spoken.firstSoundMs - stoppedTalkingAt
                _timings.value = _timings.value.record(firstSoundMs = spoken.firstSoundMs, confirmMs = confirm, engine = spoken.engine)
            }
        }
        speakJob = job
        job.invokeOnCompletion { cause -> if (cause == null) scope.launch { if (speakJob === job) dispatch(VoiceEvent.SpeechEnded) } }
    }

    private fun send(answer: VoiceAnswer) {
        val taskId = liveTaskId ?: OwnerMomentsRuntime.task.value?.taskId ?: return
        // A spoken yes approves the send that was read back, never whatever happens to be open by now.
        if (answer is VoiceAnswer.Approve) {
            val open = VoiceMomentSource.of(OwnerMomentsRuntime.current())
            if (open?.id != answer.momentId || open.kind != VoiceMoment.Kind.SEND) return
        }
        val command = when (answer) {
            is VoiceAnswer.Reply -> TaskCommand.Reply(answer.text)
            // Values said by voice are for this task only: never remembered (plan 32).
            is VoiceAnswer.Fill -> TaskCommand.Fill(answer.values, remember = false)
            is VoiceAnswer.Approve -> TaskCommand.Approve
            VoiceAnswer.Decline -> TaskCommand.Decline
            VoiceAnswer.Stop -> TaskCommand.Stop
        }
        TaskCommands.send(app, taskId, command)
    }

    // ---- the task ---------------------------------------------------------------------------------------------------

    private fun onTask(task: com.cyclone.mobile.runtime.background.WorkspaceTaskUi?) {
        val live = task != null && task.phase !in TERMINAL
        val wasLive = liveTaskId != null
        when {
            live && liveTaskId != task!!.taskId -> { liveTaskId = task.taskId; dispatch(VoiceEvent.TaskStarted) }
            !live && wasLive -> {
                liveTaskId = null
                val outcome = when (task?.phase) {
                    TaskPhase.DONE -> TaskOutcome.DONE
                    TaskPhase.FAILED -> TaskOutcome.FAILED
                    else -> TaskOutcome.STOPPED
                }
                dispatch(VoiceEvent.TaskEnded(outcome, task?.outcome ?: task?.message.orEmpty()))
            }
        }
    }

    private fun armStillWorking() {
        if (stillJob?.isActive == true) return
        stillJob = scope.launch {
            delay(STILL_WORKING_MS)
            dispatch(VoiceEvent.StillWorking)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    /** The model for one stage from the live list, fetching the list first if this session has none yet. */
    private fun model(key: String, pick: (VoiceChoice) -> String?): String? {
        val settings = DriverMode.settings.value
        pick(VoiceCatalog.choice(settings))?.let { return it }
        VoiceCatalog.refresh(key, maxAgeMs = 0)
        return pick(VoiceCatalog.choice(settings))
    }

    private fun track(call: Call) = synchronized(calls) {
        calls += call
        if (calls.size > 8) calls.removeAt(0)
    }

    private fun cancelCalls() = synchronized(calls) { calls.forEach { it.cancel() }; calls.clear() }

    companion object {
        const val STILL_WORKING_MS = 60_000L
        private const val JEV_WAIT_MS = 3_000L
        /** Drive's recording failed in a way Android's own recognizer can get around (it records in its own process). */
        private val RECOGNIZER_TAKES_OVER = setOf(VoiceFailure.MIC_SILENCED, VoiceFailure.MIC_BUSY)
        private val TERMINAL = setOf(TaskPhase.DONE, TaskPhase.FAILED, TaskPhase.STOPPED)
    }
}
