package com.cyclone.mobile.codes

import android.content.Context
import com.cyclone.mobile.DeviceState
import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.mind.MindCodes
import com.cyclone.mobile.mind.MindRef
import com.cyclone.mobile.secrets.PhoneToolSecretFillExecutor
import com.cyclone.mobile.secrets.SecretFillTarget

/** Plan 49 on Android: this phone's texts and numbers, and the same one-shot fill path the vault uses. */
class AndroidMindCodes(private val context: Context) : MindCodes {
    override fun ready(): Boolean = AndroidCodes.ready(context)
    override fun numbers(): List<String> = AndroidCodes.numbers(context)
    override fun subscriptionOf(number: String?): Int = AndroidCodes.subscriptionOf(context, number)
    override fun catcher(): CodeCatcher = CodeCatcher(AndroidCodes.inbox(context))

    override fun fill(page: AgentPageCard, target: MindRef, code: String): Boolean {
        val chars = code.toCharArray()
        return try {
            val fillTarget = SecretFillTarget(target.elementId, target.observationId, page.sessionId, page.displayId)
            val execution = PhoneToolSecretFillExecutor(context).fill(fillTarget, chars)
            execution.performed && execution.verified
        } catch (_: Exception) {
            false
        } finally {
            chars.fill('\u0000')
            DeviceState.setController(DeviceState.Controller.AGENT)
        }
    }
}
