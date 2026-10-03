package com.cyclone.mobile.voice

import com.cyclone.mobile.owner.MomentKind
import com.cyclone.mobile.owner.OwnerMoment

/** An Owner Moment as voice sees it. The moment stays the one source of truth; nothing here is stored. */
object VoiceMomentSource {
    private val SENSITIVE = Regex("(?i)password|passcode|wachtwoord|\\bpin\\b|otp|code|cvv|cvc|card|iban|account number|secret|token")

    fun of(moment: OwnerMoment?): VoiceMoment? {
        moment ?: return null
        val kind = when (moment.kind) {
            MomentKind.QUESTION -> VoiceMoment.Kind.QUESTION
            MomentKind.VALUES -> VoiceMoment.Kind.VALUES
            MomentKind.APPROVAL -> VoiceMoment.Kind.APPROVAL
            MomentKind.SECRET -> VoiceMoment.Kind.SECRET
            MomentKind.HANDOVER -> VoiceMoment.Kind.HANDOVER
        }
        val id = moment.requestId ?: "${moment.taskId}:${moment.kind}:${moment.text.hashCode()}"
        // A send is read back and approved by voice only when the approval carries the exact message and it can be
        // spoken unchanged; every other approval waits for the owner on screen.
        val send = moment.send?.takeIf { moment.gate == "send" && VoiceMoments.readable(it.text) }
        if (kind == VoiceMoment.Kind.APPROVAL && send != null) {
            val readback = VoiceMoment(id, VoiceMoment.Kind.SEND, VoiceRedaction.spoken(moment.text),
                recipient = VoiceRedaction.spoken(send.recipient).take(60), message = send.text.replace(Regex("\\s+"), " ").trim())
            // The whole spoken line must survive redaction unchanged, or it would not be the text that is sent.
            val line = VoiceMoments.readback(readback)
            if (VoiceRedaction.spoken(line) == line) return readback
            return VoiceMoment(id, VoiceMoment.Kind.APPROVAL, VoiceRedaction.spoken(moment.text))
        }
        // Details that look secret are never asked aloud: the card waits on screen, as SECRET does.
        if (kind == VoiceMoment.Kind.VALUES && moment.fields.any { SENSITIVE.containsMatchIn(it.label) }) {
            return VoiceMoment(id, VoiceMoment.Kind.SECRET, "")
        }
        // SECRET is never spoken or heard: not even its prompt.
        val text = if (kind == VoiceMoment.Kind.SECRET) "" else VoiceRedaction.spoken(moment.text)
        return VoiceMoment(id, kind, text, moment.choices, moment.fields.map { VoiceMoment.Field(it.label, it.choices) })
    }
}
