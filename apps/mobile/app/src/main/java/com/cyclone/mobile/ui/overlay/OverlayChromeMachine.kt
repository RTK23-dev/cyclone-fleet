package com.cyclone.mobile.ui.overlay

/**
 * JVM-testable overlay state machine. Buttons change Cyclone state only.
 * Host taps are not dispatched from here — PhoneToolExecutor remains the only mutation engine.
 */
class OverlayChromeMachine(
    private val emit: (OverlayChromeEvent) -> Unit = OverlayChromeBus::publish,
    private val cycloneState: OverlayCycloneStateEffects = OverlayCycloneStateEffects.NoOp,
) {
    @Volatile
    private var snapshot: OverlayChromeSnapshot = OverlayChromeSnapshot()

    fun snapshot(): OverlayChromeSnapshot = snapshot

    fun state(): OverlayChromeState = snapshot.state

    fun startAnalysis(
        sessionId: String,
        bullets: List<String> = emptyList(),
        cta: OverlayAnalysisCta = OverlayAnalysisCta.CONFIRM,
    ) {
        if (snapshot.state != OverlayChromeState.IDLE && snapshot.state != OverlayChromeState.DONE) return
        val gateContinuation = snapshot.state == OverlayChromeState.DONE &&
            snapshot.gateClass != null && snapshot.sessionId == sessionId
        snapshot = OverlayChromeSnapshot(
            state = OverlayChromeState.ANALYSIS,
            sessionId = sessionId,
            bullets = bullets,
            analysisCta = cta,
            idleChipVisible = false,
            minimized = gateContinuation && snapshot.minimized,
            launcherCollapsed = false,
        )
    }

    fun enterWorking(sessionId: String = snapshot.sessionId) {
        if (snapshot.state != OverlayChromeState.IDLE &&
            snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        val continuation = snapshot.sessionId.isNotBlank() && sessionId == snapshot.sessionId
        snapshot = snapshot.copy(
            state = OverlayChromeState.WORKING,
            sessionId = sessionId.ifBlank { snapshot.sessionId },
            idleChipVisible = false,
            minimized = continuation && snapshot.minimized,
            launcherCollapsed = continuation && snapshot.launcherCollapsed,
            userPaused = false,
        )
        cycloneState.resumeAgent()
    }

    /** Once per new task; later status updates must respect the user's manual collapse. */
    fun showTaskProgress() {
        snapshot = snapshot.copy(
            state = if (snapshot.state == OverlayChromeState.IDLE) OverlayChromeState.ANALYSIS else snapshot.state,
            minimized = false, launcherCollapsed = false, idleChipVisible = false,
        )
    }

    fun enterLive() {
        if (snapshot.state != OverlayChromeState.WORKING) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.LIVE,
            idleChipVisible = false,
            minimized = false,
            launcherCollapsed = false,
        )
    }

    fun enterGate(gateClass: OverlayGateClass, pcAutoApprove: Boolean = false, sessionId: String = snapshot.sessionId) {
        if (snapshot.state == OverlayChromeState.GATE) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.GATE,
            sessionId = sessionId.ifBlank { snapshot.sessionId },
            gateClass = gateClass,
            idleChipVisible = false,
            pcAutoApproveIgnored = pcAutoApprove,
            minimized = false,
            launcherCollapsed = false,
            userPaused = true,
            composerText = "",
            voiceListening = false,
            voiceMessage = null,
        )
        cycloneState.pauseAgentForUser()
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.GATE,
                state = OverlayChromeState.GATE,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
                pcAutoApproveIgnored = pcAutoApprove,
            ),
        )
    }

    /** A stopped run is terminal for the run, not for the composer. */
    fun finishStopped(message: String) {
        // The stop path pauses input immediately so no queued action can race cancellation.
        // Once the run is terminal, release that temporary pause. An explicit owner takeover
        // remains human-owned until the owner hands the phone back.
        if (!snapshot.userPaused) cycloneState.resumeAgent()
        snapshot = snapshot.copy(
            state = OverlayChromeState.ANALYSIS,
            userPaused = snapshot.userPaused,
            minimized = true,
            launcherCollapsed = false,
            idleChipVisible = false,
            statusMessage = null,
            bullets = emptyList(),
            composerText = "",
            voiceListening = false,
            voiceMessage = null,
        )
    }

    /** Completion emits DONE, then returns the overlay to a clean request-ready state. */
    fun completeDone(sessionId: String = snapshot.sessionId) {
        if (snapshot.state != OverlayChromeState.WORKING && snapshot.state != OverlayChromeState.LIVE) return
        val finishedSession = sessionId.ifBlank { snapshot.sessionId }
        snapshot = snapshot.copy(
            state = OverlayChromeState.ANALYSIS,
            sessionId = finishedSession,
            idleChipVisible = true,
            minimized = true,
            launcherCollapsed = false,
            userPaused = false,
            composerText = "",
            voiceListening = false,
            voiceMessage = null,
            statusMessage = null,
            bullets = emptyList(),
            gateClass = null,
        )
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.DONE,
                state = OverlayChromeState.DONE,
                sessionId = finishedSession,
            ),
        )
    }

    fun resetIdle(idleChipVisible: Boolean = true) {
        snapshot = OverlayChromeSnapshot(idleChipVisible = idleChipVisible)
    }

    fun dispatch(action: OverlayUserAction) {
        when (action) {
            OverlayUserAction.ASK_CYCLONE -> askCyclone()
            OverlayUserAction.CONFIRM -> confirm()
            OverlayUserAction.COMMERCE -> commerce()
            OverlayUserAction.VIEW_PROGRESS -> viewProgress()
            OverlayUserAction.STOP_TASK -> stopTask()
            OverlayUserAction.TAKE_CONTROL -> takeControl()
            OverlayUserAction.GATE_CONFIRM -> gateConfirm()
            OverlayUserAction.MINIMIZE -> minimize()
            OverlayUserAction.EXIT -> exitAiMode()
        }
    }

    private fun askCyclone() {
        if (snapshot.launcherCollapsed) {
            // Triple-tap recovery returns to the request-ready composer first, not the full drawer.
            snapshot = snapshot.copy(
                minimized = true,
                launcherCollapsed = false,
                idleChipVisible = false,
                statusMessage = null,
                voiceMessage = null,
            )
            return
        }
        if (snapshot.minimized) {
            snapshot = snapshot.copy(
                minimized = false,
                launcherCollapsed = false,
                idleChipVisible = false,
                statusMessage = null,
                voiceMessage = null,
            )
            return
        }
        if (snapshot.state == OverlayChromeState.DONE) {
            snapshot = OverlayChromeSnapshot(
                state = OverlayChromeState.ANALYSIS,
                idleChipVisible = false,
                minimized = false,
                launcherCollapsed = false,
            )
            emitChrome(OverlayChromeEventKind.ASK_CYCLONE)
            return
        }
        if (snapshot.state != OverlayChromeState.IDLE) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.ANALYSIS,
            idleChipVisible = false,
            minimized = false,
            launcherCollapsed = false,
            userPaused = false,
            statusMessage = null,
            composerText = "",
            voiceMessage = null,
        )
        emitChrome(OverlayChromeEventKind.ASK_CYCLONE)
    }

    fun updateComposer(text: String) {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        snapshot = snapshot.copy(composerText = text.take(2_000), voiceMessage = null)
    }

    fun submitRequest(text: String = snapshot.composerText) {
        val request = text.trim().take(2_000)
        if (request.isBlank() ||
            (snapshot.state != OverlayChromeState.ANALYSIS &&
                snapshot.state != OverlayChromeState.WORKING &&
                snapshot.state != OverlayChromeState.LIVE)
        ) return
        snapshot = snapshot.copy(
            composerText = "",
            bullets = listOf(request),
            voiceMessage = null,
            statusMessage = null,
        )
        emitChrome(OverlayChromeEventKind.ASK_CYCLONE, requestText = request)
    }

    fun updateVoice(listening: Boolean, transcript: String? = null, message: String? = null) {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        snapshot = snapshot.copy(
            voiceListening = listening,
            composerText = transcript?.take(2_000) ?: snapshot.composerText,
            voiceMessage = message,
        )
    }

    fun updateStatus(message: String?) {
        if (snapshot.state == OverlayChromeState.IDLE || snapshot.state == OverlayChromeState.GATE) return
        snapshot = snapshot.copy(statusMessage = message?.take(500))
    }

    private fun confirm() {
        if (snapshot.state != OverlayChromeState.ANALYSIS || snapshot.analysisCta != OverlayAnalysisCta.CONFIRM) return
        snapshot = snapshot.copy(state = OverlayChromeState.WORKING, idleChipVisible = false, userPaused = false)
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.CONFIRM)
    }

    private fun commerce() {
        if (snapshot.state != OverlayChromeState.ANALYSIS || snapshot.analysisCta != OverlayAnalysisCta.COMMERCE) return
        snapshot = snapshot.copy(state = OverlayChromeState.WORKING, idleChipVisible = false, userPaused = false)
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.COMMERCE)
    }

    private fun viewProgress() {
        if (snapshot.state != OverlayChromeState.WORKING) return
        snapshot = snapshot.copy(
            state = OverlayChromeState.LIVE,
            idleChipVisible = false,
            minimized = false,
            launcherCollapsed = false,
        )
        emitChrome(OverlayChromeEventKind.VIEW_PROGRESS)
    }

    private fun stopTask() {
        if (snapshot.state != OverlayChromeState.WORKING && snapshot.state != OverlayChromeState.LIVE) return
        val from = snapshot.state
        val session = snapshot.sessionId
        cycloneState.pauseAgentForUser()
        snapshot = OverlayChromeSnapshot(sessionId = session, idleChipVisible = true)
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.STOP_TASK,
                state = from,
                sessionId = session,
            ),
        )
    }

    private fun takeControl() {
        if (snapshot.state != OverlayChromeState.ANALYSIS &&
            snapshot.state != OverlayChromeState.WORKING &&
            snapshot.state != OverlayChromeState.LIVE
        ) return
        val returningToAgent = snapshot.userPaused
        if (returningToAgent) cycloneState.resumeAgent() else cycloneState.pauseAgentForUser()
        snapshot = snapshot.copy(
            userPaused = !snapshot.userPaused,
            minimized = if (returningToAgent) true else snapshot.minimized,
            launcherCollapsed = if (returningToAgent) false else snapshot.launcherCollapsed,
            idleChipVisible = if (returningToAgent) false else snapshot.idleChipVisible,
        )
        emitChrome(OverlayChromeEventKind.TAKE_CONTROL)
    }

    /**
     * Presentation-only collapse. First collapse keeps a fully usable Ask Cyclone composer.
     * A second collapse becomes the tiny triple-tap launcher. Neither transition releases task ownership.
     */
    private fun minimize() {
        if (snapshot.state == OverlayChromeState.IDLE) return
        if (!snapshot.minimized) {
            snapshot = snapshot.copy(
                minimized = true,
                launcherCollapsed = false,
                idleChipVisible = false,
                voiceListening = false,
                voiceMessage = null,
            )
            return
        }
        if (!snapshot.launcherCollapsed) {
            // Plan 27: folding the island away while a task runs leaves only its live notification (Show brings the
            // island back); with nothing running it is the tiny triple-tap launcher, as before.
            val running = snapshot.state == OverlayChromeState.WORKING || snapshot.state == OverlayChromeState.LIVE ||
                snapshot.state == OverlayChromeState.GATE
            snapshot = snapshot.copy(
                minimized = true,
                launcherCollapsed = true,
                idleChipVisible = !running,
                voiceListening = false,
                voiceMessage = null,
            )
        }
    }

    private fun exitAiMode() {
        val from = snapshot.state
        if (from == OverlayChromeState.IDLE) {
            resetIdle(idleChipVisible = false)
            return
        }
        if (from != OverlayChromeState.DONE) {
            cycloneState.pauseAgentForUser()
            emit(
                OverlayChromeEvent(
                    kind = OverlayChromeEventKind.STOP_TASK,
                    state = from,
                    sessionId = snapshot.sessionId,
                    gateClass = snapshot.gateClass,
                ),
            )
        }
        resetIdle()
    }

    /** The owner said no to the exact action on the approval card: back to work without it. */
    fun gateDecline() {
        if (snapshot.state != OverlayChromeState.GATE) return
        cycloneState.resumeAgent()
        snapshot = snapshot.copy(state = OverlayChromeState.WORKING, gateClass = null, userPaused = false, minimized = true, launcherCollapsed = false)
    }

    private fun gateConfirm() {
        if (snapshot.state != OverlayChromeState.GATE) return
        val gateClass = snapshot.gateClass
        cycloneState.resumeAgent()
        emitChrome(OverlayChromeEventKind.GATE_CONFIRM, gateClass = gateClass)
        snapshot = snapshot.copy(
            state = OverlayChromeState.DONE,
            idleChipVisible = false,
            minimized = true,
            launcherCollapsed = false,
            userPaused = false,
        )
        emit(
            OverlayChromeEvent(
                kind = OverlayChromeEventKind.DONE,
                state = OverlayChromeState.DONE,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
            ),
        )
    }

    private fun emitChrome(
        kind: OverlayChromeEventKind,
        gateClass: OverlayGateClass? = snapshot.gateClass,
        requestText: String? = null,
    ) {
        emit(
            OverlayChromeEvent(
                kind = kind,
                state = snapshot.state,
                sessionId = snapshot.sessionId,
                gateClass = gateClass,
                pcAutoApproveIgnored = snapshot.pcAutoApproveIgnored,
                requestText = requestText,
                userPaused = snapshot.userPaused,
            ),
        )
    }
}

interface OverlayCycloneStateEffects {
    fun pauseAgentForUser()
    fun resumeAgent()

    object NoOp : OverlayCycloneStateEffects {
        override fun pauseAgentForUser() = Unit
        override fun resumeAgent() = Unit
    }
}
