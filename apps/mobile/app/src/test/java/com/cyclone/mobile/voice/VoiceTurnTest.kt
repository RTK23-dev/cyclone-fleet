package com.cyclone.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every path of the turn-taking engine, including the ones that must cost no network call. */
class VoiceTurnTest {
    private class Run(var turn: VoiceTurn = VoiceTurn()) {
        val effects = mutableListOf<VoiceEffect>()
        fun on(event: VoiceEvent): List<VoiceEffect> {
            val step = turn.on(event)
            turn = step.turn
            effects += step.effects
            return step.effects
        }
        /** The effects that would reach the network: transcription and understanding. */
        val calls get() = effects.count { it is VoiceEffect.Transcribe || it is VoiceEffect.Understand }
        val said get() = effects.filterIsInstance<VoiceEffect.Say>().map { it.line }
    }

    private fun understood(kind: VoiceKind, goal: String = "", ack: String = "", missing: String = "") =
        VoiceEvent.Understood(Understanding(kind, goal, ack, missing, 0.9))

    @Test fun `a tap plays the earcon and listens`() {
        val r = Run()
        assertEquals(listOf(VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen), r.on(VoiceEvent.Tap))
        assertEquals(VoicePhase.LISTENING, r.turn.phase)
        assertTrue(r.turn.panelOpen)
        assertTrue(r.turn.dimmed)
    }

    @Test fun `silence closes with no call`() {
        val r = Run()
        r.on(VoiceEvent.Tap)
        r.on(VoiceEvent.NothingHeard)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        assertEquals(0, r.calls)
        assertTrue(r.effects.contains(VoiceEffect.Play(Earcon.CLOSE_SOFT)))
        assertTrue(r.said.isEmpty())
    }

    @Test fun `filler and cancel make no understanding call`() {
        for (text in listOf("uh", "hmm", "Thank you.", "never mind", "yes")) {
            val r = Run()
            r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript(text))
            assertEquals(text, 0, r.effects.count { it is VoiceEffect.Understand })
            assertFalse(text, r.turn.taskLive)
        }
        val cancel = Run()
        cancel.on(VoiceEvent.Tap); cancel.on(VoiceEvent.Heard); cancel.on(VoiceEvent.Transcript("cancel"))
        assertEquals(listOf(VoiceCopy.OKAY), cancel.said)
        cancel.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.CLOSED, cancel.turn.phase)
    }

    @Test fun `a task is confirmed, submitted, and the panel collapses while working`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard)
        r.on(VoiceEvent.Transcript("uh navigate home and avoid the highway"))
        val understand = r.effects.last() as VoiceEffect.Understand
        assertEquals("navigate home and avoid the highway", understand.transcript)
        val fx = r.on(understood(VoiceKind.TASK, "Navigate home avoiding highways.", "Navigating home."))
        assertEquals(VoiceEffect.Submit("Navigate home avoiding highways."), fx.first())
        assertEquals(VoiceEffect.Say("Navigating home.", AfterSpeech.WORK), fx[1])
        assertEquals(VoicePhase.ACKING, r.turn.phase)
        assertTrue(r.turn.taskLive)
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.WORKING, r.turn.phase)
        assertFalse(r.turn.panelOpen)
        assertFalse(r.turn.dimmed)
    }

    @Test fun `an instant command confirms without a model call`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard)
        val fx = r.on(VoiceEvent.Transcript("set a timer for ten minutes"))
        assertEquals(0, r.effects.count { it is VoiceEffect.Understand })
        assertEquals(VoiceEffect.Submit("Set a timer for 10 minutes."), fx.first())
        assertEquals(VoiceEffect.Say("Setting a 10 minute timer.", AfterSpeech.WORK), fx[1])
    }

    @Test fun `an instant command is not taken while Cyclone asks something`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "How long should the timer be?")))
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard)
        val before = r.effects.count { it is VoiceEffect.Understand }
        r.on(VoiceEvent.Transcript("ten minute timer"))
        assertEquals(before + 1, r.effects.count { it is VoiceEffect.Understand })
    }

    @Test fun `done is announced with a chime and one short line`() {
        val r = working()
        val fx = r.on(VoiceEvent.TaskEnded(TaskOutcome.DONE, "Timer set for 10 minutes."))
        assertEquals(VoiceEffect.Play(Earcon.DONE), fx[0])
        assertEquals(VoiceEffect.Say("Done: timer set for 10 minutes.", AfterSpeech.CLOSE), fx[1])
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        assertFalse(r.turn.taskLive)
    }

    @Test fun `still working is said once`() {
        val r = working()
        r.on(VoiceEvent.StillWorking)
        r.on(VoiceEvent.SpeechEnded)
        r.on(VoiceEvent.StillWorking)
        assertEquals(1, r.said.count { it == VoiceCopy.STILL_WORKING })
        assertEquals(VoicePhase.WORKING, r.turn.phase)
    }

    @Test fun `stop ends the turn and the task through task kit`() {
        val r = working()
        r.on(VoiceEvent.Tap)
        val fx = r.on(VoiceEvent.Stop)
        assertTrue(fx.contains(VoiceEffect.StopListening))
        assertTrue(fx.contains(VoiceEffect.Send(VoiceAnswer.Stop)))
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        // The owner stopped it: no "Stopped." afterwards.
        assertTrue(r.on(VoiceEvent.TaskEnded(TaskOutcome.STOPPED)).isEmpty())
    }

    @Test fun `saying stop while a task runs stops it`() {
        val r = working()
        val before = r.effects.count { it is VoiceEffect.Understand }
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard)
        val fx = r.on(VoiceEvent.Transcript("Stop."))
        assertEquals(VoiceEffect.Send(VoiceAnswer.Stop), fx.first())
        assertEquals(before, r.effects.count { it is VoiceEffect.Understand })
    }

    @Test fun `unclear asks one question, then gives up politely`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("text my mom"))
        r.on(understood(VoiceKind.UNCLEAR, missing = "What should I tell your mom?"))
        assertEquals(VoicePhase.ASKING, r.turn.phase)
        assertEquals(listOf(VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen), r.on(VoiceEvent.SpeechEnded))
        r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("that I'm late"))
        val second = r.effects.last() as VoiceEffect.Understand
        assertEquals(VoiceContext.FollowUp("text my mom", "What should I tell your mom?"), second.context.followUp)
        r.on(understood(VoiceKind.UNCLEAR, missing = "Which mom?"))
        assertEquals(VoiceCopy.UNCLEAR_AGAIN, r.said.last())
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
    }

    @Test fun `a follow up answer that is clear starts the task`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("text my mom"))
        r.on(understood(VoiceKind.UNCLEAR, missing = "What should I tell your mom?"))
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("that I'm late"))
        r.on(understood(VoiceKind.REPLY, "Text mom: I'm late.", "Texting your mom."))
        assertTrue(r.effects.contains(VoiceEffect.Submit("Text mom: I'm late.")))
        assertEquals(null, r.turn.followUp)
    }

    @Test fun `a second request while one runs is refused`() {
        val r = working()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("open maps"))
        r.on(understood(VoiceKind.TASK, "Open Maps.", "Opening Maps."))
        assertEquals(1, r.effects.count { it is VoiceEffect.Submit })
        assertEquals(VoiceCopy.BUSY, r.said.last())
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.WORKING, r.turn.phase)
    }

    @Test fun `a task that ends while the owner talks is said afterwards`() {
        val r = working()
        r.on(VoiceEvent.Tap)
        assertTrue(r.on(VoiceEvent.TaskEnded(TaskOutcome.DONE, "Timer set.")).isEmpty())
        r.on(VoiceEvent.NothingHeard)
        // NothingHeard rests; the pending end is said when the turn is next free.
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("cancel"))
        r.on(VoiceEvent.SpeechEnded)
        assertEquals("Done: timer set.", r.said.last())
    }

    @Test fun `a tap while Cyclone speaks interrupts and listens`() {
        val r = working()
        r.on(VoiceEvent.TaskEnded(TaskOutcome.DONE, "All done."))
        val fx = r.on(VoiceEvent.Tap)
        assertEquals(listOf(VoiceEffect.StopSpeaking, VoiceEffect.Play(Earcon.LISTEN), VoiceEffect.Listen), fx)
        assertEquals(VoicePhase.LISTENING, r.turn.phase)
    }

    @Test fun `taps while transcribing or understanding are ignored`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard)
        assertTrue(r.on(VoiceEvent.Tap).isEmpty())
        // A request for the understanding model (a quick command like "open maps" goes straight to the router).
        r.on(VoiceEvent.Transcript("find a gas station on the way"))
        assertTrue(r.on(VoiceEvent.Tap).isEmpty())
    }

    @Test fun `failures are spoken and close`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard)
        r.on(VoiceEvent.Failed(VoiceFailure.OFFLINE))
        assertEquals(VoiceCopy.OFFLINE, r.said.last())
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
    }

    @Test fun `a silenced microphone says so instead of a generic miss`() {
        val r = Run()
        r.on(VoiceEvent.Tap)
        r.on(VoiceEvent.Failed(VoiceFailure.MIC_SILENCED))
        assertEquals(VoiceCopy.MIC_SILENCED, r.said.last())
    }

    @Test fun `none closes quietly`() {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("what a nice day"))
        r.on(understood(VoiceKind.NONE))
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        assertTrue(r.said.isEmpty())
    }

    // ---- moments ----------------------------------------------------------------------------------------------------

    @Test fun `a question is spoken, answered by voice, and sent as a reply`() {
        val r = working()
        val fx = r.on(VoiceEvent.MomentOpened(VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "Louella wrote \"I will be home late\". How should I answer?")))
        assertEquals(VoiceEffect.Play(Earcon.NEEDS_YOU), fx[0])
        assertEquals(VoicePhase.ASKING, r.turn.phase)
        assertTrue(r.turn.panelOpen)
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.LISTENING, r.turn.phase)
        r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("tell her that's alright"))
        val understand = r.effects.last() as VoiceEffect.Understand
        assertEquals("question", understand.context.open?.kind)
        r.on(understood(VoiceKind.ANSWER, "That's alright, hope to see her soon."))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Reply("That's alright, hope to see her soon."))))
        assertEquals(null, r.turn.moment)
    }

    @Test fun `approvals, secrets and handovers are never answered by voice`() {
        for (kind in listOf(VoiceMoment.Kind.APPROVAL, VoiceMoment.Kind.SECRET, VoiceMoment.Kind.HANDOVER)) {
            val r = working()
            r.on(VoiceEvent.MomentOpened(VoiceMoment("m-$kind", kind, "Pay 20 euro to Bol.com?")))
            assertEquals(VoiceCopy.NEEDS_SCREEN, r.said.last())
            r.on(VoiceEvent.SpeechEnded)
            // Cyclone does not listen for an answer.
            assertFalse(r.effects.takeLast(2).contains(VoiceEffect.Listen))
            assertEquals(VoicePhase.WORKING, r.turn.phase)
            // Even if the owner taps and says yes, nothing is approved.
            r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("yes approve it"))
            r.on(understood(VoiceKind.CONFIRM))
            assertFalse(kind.name, r.effects.any { it is VoiceEffect.Send && it.answer is VoiceAnswer.Approve })
        }
    }

    @Test fun `one moment is spoken once`() {
        val r = working()
        val m = VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "Which one?")
        r.on(VoiceEvent.MomentOpened(m))
        assertTrue(r.on(VoiceEvent.MomentOpened(m)).isEmpty())
    }

    @Test fun `a moment that opens while the owner talks waits its turn`() {
        val r = working()
        r.on(VoiceEvent.Tap)
        assertTrue(r.on(VoiceEvent.MomentOpened(VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "Which one?"))).isEmpty())
        r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("never mind"))
        // "never mind" with a question open declines it.
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Decline)))
    }

    @Test fun `not now declines the open question`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "Which one?")))
        val fx = r.on(VoiceEvent.NotNow)
        assertTrue(fx.contains(VoiceEffect.Send(VoiceAnswer.Decline)))
        assertTrue(fx.contains(VoiceEffect.StopSpeaking))
        assertEquals(VoicePhase.WORKING, r.turn.phase)
    }

    // ---- plan 32 D2: conversations ------------------------------------------------------------------------------------

    private val louella = VoiceMoment("a1", VoiceMoment.Kind.SEND, "Cyclone wants to: send a reply", recipient = "Louella",
        message = "Hey baby, that's alright, hope to see you soon for the movie.")

    @Test fun `a send is read back verbatim and a yes approves exactly that moment`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella))
        assertEquals("I'll send Louella: \"Hey baby, that's alright, hope to see you soon for the movie.\". Send it?", r.said.last())
        assertTrue(r.said.last().contains(louella.message))
        assertEquals(VoicePhase.READBACK, r.turn.phase)
        val calls = r.calls
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("Yes, send it."))
        // An explicit yes needs no model: only the transcription call was made.
        assertEquals(calls + 1, r.calls)
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Approve("a1"))))
        assertEquals(VoiceMoments.SENDING, r.said.last())
    }

    @Test fun `no declines the send`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella)); r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("no don't"))
        r.on(understood(VoiceKind.DECLINE))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Decline)))
        assertFalse(r.effects.any { it is VoiceEffect.Send && it.answer is VoiceAnswer.Approve })
        assertEquals(VoiceMoments.NOT_SENT, r.said.last())
    }

    @Test fun `change it asks Cyclone for an edit, then reads the new text back`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella)); r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard)
        r.on(VoiceEvent.Transcript("change the end to see you tonight"))
        r.on(understood(VoiceKind.ANSWER, "Change the end to: see you tonight."))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Reply("Change the end to: see you tonight."))))
        assertFalse(r.effects.any { it is VoiceEffect.Send && it.answer is VoiceAnswer.Approve })
        r.on(VoiceEvent.SpeechEnded)
        val edited = louella.copy(id = "a2", message = "Hey baby, that's alright, see you tonight.")
        r.on(VoiceEvent.MomentOpened(edited))
        assertEquals("I'll send Louella: \"Hey baby, that's alright, see you tonight.\". Send it?", r.said.last())
    }

    @Test fun `a bare yes is enough for a readback but never opens a new task`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella)); r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("Yes."))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Approve("a1"))))
        assertEquals(1, r.effects.count { it is VoiceEffect.Submit })
    }

    @Test fun `a yes the model calls an answer still approves, never becomes an edit`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella)); r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("uh yeah okay go on"))
        r.on(understood(VoiceKind.ANSWER, "yes"))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Approve("a1"))))
        assertFalse(r.effects.any { it is VoiceEffect.Send && it.answer is VoiceAnswer.Reply })
    }

    @Test fun `a plain no declines without a model call`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella)); r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard)
        val understands = r.effects.count { it is VoiceEffect.Understand }
        r.on(VoiceEvent.Transcript("No."))
        assertEquals(understands, r.effects.count { it is VoiceEffect.Understand })
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Decline)))
    }

    @Test fun `only explicit phrases count as yes`() {
        assertEquals(true, VoiceRules.yesNo("Yes, send it."))
        assertEquals(true, VoiceRules.yesNo("ja stuur maar"))
        assertEquals(false, VoiceRules.yesNo("no"))
        assertEquals(null, VoiceRules.yesNo("yes but change the end"))
        assertEquals(null, VoiceRules.yesNo("maybe"))
        assertEquals(null, VoiceRules.yesNo(""))
    }

    @Test fun `details are asked one field at a time and never remembered`() {
        val r = working()
        val card = VoiceMoment("v1", VoiceMoment.Kind.VALUES, "Cyclone needs a few details",
            fields = listOf(VoiceMoment.Field("Date"), VoiceMoment.Field("Time", listOf("Morning", "Evening"))))
        r.on(VoiceEvent.MomentOpened(card))
        assertEquals("What's the date?", r.said.last())
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("next friday"))
        assertEquals("details", (r.effects.last() as VoiceEffect.Understand).context.open?.kind)
        r.on(understood(VoiceKind.ANSWER, "next Friday"))
        assertEquals("What's the time? Morning, or Evening?", r.said.last())
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("evening"))
        r.on(understood(VoiceKind.ANSWER, "Evening"))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Fill(mapOf("Date" to "next Friday", "Time" to "Evening")))))
        assertTrue(r.turn.filled.isEmpty())
    }

    @Test fun `a second unclear answer to a question closes politely and leaves the card`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(VoiceMoment("q1", VoiceMoment.Kind.QUESTION, "How should I answer?")))
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("hmm well the thing"))
        r.on(understood(VoiceKind.UNCLEAR, missing = "What should I tell her?"))
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("the other thing"))
        r.on(understood(VoiceKind.UNCLEAR))
        assertEquals(VoiceCopy.UNCLEAR_AGAIN, r.said.last())
        assertFalse(r.effects.any { it is VoiceEffect.Send })
    }

    @Test fun `readable drafts only - nothing masked, nothing too long, no quotes inside`() {
        assertTrue(VoiceMoments.readable("Hey baby, that's alright, see you tonight."))
        assertFalse(VoiceMoments.readable("my code is 482913"))
        assertFalse(VoiceMoments.readable("word ".repeat(41)))
        assertFalse(VoiceMoments.readable("she said \"hi\""))
        assertFalse(VoiceMoments.readable("   "))
    }

    @Test fun `every spoken line is redacted`() {
        val r = working()
        r.on(VoiceEvent.TaskEnded(TaskOutcome.DONE, "Your code is 482913 and your password: hunter22."))
        assertFalse(r.said.last(), r.said.last().contains("482913"))
        assertFalse(r.said.last(), r.said.last().contains("hunter22"))
    }

    // ---- D3: a readback cut short, and announced messages ------------------------------------------------------------

    @Test fun `a readback cut short by a tap is read again before a yes counts`() {
        val r = working()
        r.on(VoiceEvent.MomentOpened(louella))
        // The owner taps while the readback is still playing, and says yes.
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("yes"))
        assertFalse(r.effects.any { it is VoiceEffect.Send && it.answer is VoiceAnswer.Approve })
        assertEquals(VoiceMoments.readback(louella), r.said.last())
        assertEquals(VoicePhase.READBACK, r.turn.phase)
        // Heard to the end this time: now the yes approves it.
        r.on(VoiceEvent.SpeechEnded); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("yes"))
        assertTrue(r.effects.contains(VoiceEffect.Send(VoiceAnswer.Approve("a1"))))
    }

    private val offer = VoiceOffer("n1", "Louella wrote: \"I will be home late.\" Reply?", "Louella", "WhatsApp",
        "Reply to the latest WhatsApp message from \"Louella\".")

    @Test fun `an announcement is said and closes without opening the microphone`() {
        val r = Run()
        val fx = r.on(VoiceEvent.Announce(offer))
        assertEquals(listOf(VoiceEffect.Play(Earcon.NEEDS_YOU), VoiceEffect.Say(offer.line, AfterSpeech.CLOSE)), fx)
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
        assertFalse(r.effects.contains(VoiceEffect.Listen))
        assertEquals(offer, r.turn.offer)
        assertTrue(VoiceFace.of(r.turn).warm)
    }

    @Test fun `yes after an announcement starts the reply with no model call`() {
        val r = Run()
        r.on(VoiceEvent.Announce(offer)); r.on(VoiceEvent.SpeechEnded)
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("Yes."))
        assertEquals(0, r.effects.count { it is VoiceEffect.Understand })
        assertTrue(r.effects.contains(VoiceEffect.Submit(offer.replyGoal!!)))
        assertEquals("Replying to Louella.", r.said.last())
        assertEquals(null, r.turn.offer)
    }

    @Test fun `saying what to answer goes into the reply goal`() {
        val r = Run()
        r.on(VoiceEvent.Announce(offer)); r.on(VoiceEvent.SpeechEnded)
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("tell her I'm on my way"))
        val ask = r.effects.filterIsInstance<VoiceEffect.Understand>().single()
        // The model hears who wrote, never the message itself.
        assertEquals("reply offer", ask.context.open?.kind)
        assertFalse(ask.context.open!!.spoken.contains("home late"))
        r.on(understood(VoiceKind.ANSWER, "I'm on my way"))
        val submit = r.effects.filterIsInstance<VoiceEffect.Submit>().single()
        assertEquals(VoiceAnnounce.replyWith(offer, "I'm on my way"), submit.goal)
    }

    @Test fun `no lets the message go and a new request is its own task`() {
        val no = Run()
        no.on(VoiceEvent.Announce(offer)); no.on(VoiceEvent.SpeechEnded)
        no.on(VoiceEvent.Tap); no.on(VoiceEvent.Heard); no.on(VoiceEvent.Transcript("no"))
        assertEquals(VoiceCopy.OKAY, no.said.last())
        assertFalse(no.effects.any { it is VoiceEffect.Submit })
        assertEquals(null, no.turn.offer)

        val other = Run()
        other.on(VoiceEvent.Announce(offer)); other.on(VoiceEvent.SpeechEnded)
        other.on(VoiceEvent.Tap); other.on(VoiceEvent.Heard); other.on(VoiceEvent.Transcript("navigate home"))
        other.on(understood(VoiceKind.TASK, "Navigate home.", "Navigating home."))
        assertEquals(listOf(VoiceEffect.Submit("Navigate home.")), other.effects.filterIsInstance<VoiceEffect.Submit>())
    }

    @Test fun `an announcement is dropped while busy, and expires`() {
        val busy = working()
        assertTrue(busy.on(VoiceEvent.Announce(offer)).isEmpty())
        val listening = Run()
        listening.on(VoiceEvent.Tap)
        assertTrue(listening.on(VoiceEvent.Announce(offer)).isEmpty())

        val r = Run()
        r.on(VoiceEvent.Announce(offer)); r.on(VoiceEvent.SpeechEnded)
        r.on(VoiceEvent.OfferExpired("n1"))
        assertEquals(null, r.turn.offer)
        assertFalse(VoiceFace.of(r.turn).warm)
    }

    @Test fun `a group or a message with no reply field is said without an offer`() {
        val r = Run()
        r.on(VoiceEvent.Announce(offer.copy(line = "New message in Family on WhatsApp.", replyGoal = null)))
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(null, r.turn.offer)
        assertEquals(VoicePhase.CLOSED, r.turn.phase)
    }

    private fun working(): Run {
        val r = Run()
        r.on(VoiceEvent.Tap); r.on(VoiceEvent.Heard); r.on(VoiceEvent.Transcript("set a timer for ten minutes"))
        r.on(understood(VoiceKind.TASK, "Set a timer for 10 minutes.", "Setting a timer."))
        r.on(VoiceEvent.SpeechEnded)
        assertEquals(VoicePhase.WORKING, r.turn.phase)
        return r
    }
}
