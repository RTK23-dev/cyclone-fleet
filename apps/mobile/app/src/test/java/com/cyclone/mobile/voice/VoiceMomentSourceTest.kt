package com.cyclone.mobile.voice

import com.cyclone.mobile.mind.mission.OwnerField
import com.cyclone.mobile.mind.mission.OwnerSend
import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment
import com.cyclone.mobile.task.TaskEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 32: the readback is the approval's own exact text, and only a readable send is ever approvable by voice. */
class VoiceMomentSourceTest {
    private fun approval(gate: String?, send: OwnerSend?) = OwnerMoment("mission-1", TaskEngine.MIND, MomentKind.APPROVAL,
        "Cyclone wants to: send a reply", emptyList(), requestId = "req-1", gate = gate, send = send)

    @Test fun `the readback equals the text that is sent`() {
        val sent = "Hey baby, that's alright,\nhope to see you soon for the movie."
        val moment = VoiceMomentSource.of(approval("send", OwnerSend(sent, "Louella", "WhatsApp")))!!
        assertEquals(VoiceMoment.Kind.SEND, moment.kind)
        assertEquals("req-1", moment.id)
        val line = VoiceMoments.readback(moment)
        // Word for word: only line breaks become spaces when spoken.
        assertTrue(line, line.contains("\"${sent.replace("\n", " ")}\""))
        assertEquals(line, VoiceRedaction.spoken(line))
        assertEquals(line, VoiceMoments.prompt(moment))
    }

    @Test fun `other approvals, unknown gates and unreadable drafts stay on screen`() {
        assertEquals(VoiceMoment.Kind.APPROVAL, VoiceMomentSource.of(approval("pay", null))!!.kind)
        assertEquals(VoiceMoment.Kind.APPROVAL, VoiceMomentSource.of(approval(null, OwnerSend("Hi", "Louella", "WhatsApp")))!!.kind)
        assertEquals(VoiceMoment.Kind.APPROVAL, VoiceMomentSource.of(approval("send", OwnerSend("the code is 482913", "Bank", "SMS")))!!.kind)
        assertEquals(VoiceMoment.Kind.APPROVAL, VoiceMomentSource.of(approval("send", OwnerSend("word ".repeat(60), "Louella", "WhatsApp")))!!.kind)
        assertEquals(VoiceCopy.NEEDS_SCREEN, VoiceMoments.prompt(VoiceMomentSource.of(approval("pay", null))!!))
    }

    @Test fun `secrets are never spoken and secret-looking details wait on screen`() {
        val secret = VoiceMomentSource.of(OwnerMoment("mission-1", TaskEngine.MIND, MomentKind.SECRET, "Type your bank password", emptyList(), requestId = "r"))!!
        assertEquals("", secret.text)
        val pin = VoiceMomentSource.of(OwnerMoment("mission-1", TaskEngine.MIND, MomentKind.VALUES, "Details", emptyList(),
            fields = listOf(OwnerField("Name"), OwnerField("PIN")), requestId = "v"))!!
        assertEquals(VoiceMoment.Kind.SECRET, pin.kind)
        val ok = VoiceMomentSource.of(OwnerMoment("mission-1", TaskEngine.MIND, MomentKind.VALUES, "Details", emptyList(),
            fields = listOf(OwnerField("Date"), OwnerField("Guests", choices = listOf("2", "4"))), requestId = "v2"))!!
        assertEquals(listOf(VoiceMoment.Field("Date"), VoiceMoment.Field("Guests", listOf("2", "4"))), ok.fields)
    }
}
