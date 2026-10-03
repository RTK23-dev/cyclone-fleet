package com.cyclone.mobile.mind

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.codes.CodeCatcher

/**
 * Plan 49: codes sent by text to this phone's own number, filled without asking when the run plainly uses that number
 * (`codes.AutoCodePolicy`). The Mind never sees a code: the toolbox catches it and fills it through [fill].
 * Present for owner missions; null in Lab missions. Android: `codes.AndroidMindCodes`.
 */
interface MindCodes {
    /** Settings → Codes is on and Cyclone may read texts. */
    fun ready(): Boolean

    /** This phone's numbers (SIMs and the ones the owner confirmed). */
    fun numbers(): List<String>

    /** The SIM a number belongs to, or -1. */
    fun subscriptionOf(number: String?): Int

    /** Waits for the code; its inbox reads this phone's texts in memory only. */
    fun catcher(): CodeCatcher

    /** Fills [code] into [target] on [page] (the current observation). True when the field took it. */
    fun fill(page: AgentPageCard, target: MindRef, code: String): Boolean
}
