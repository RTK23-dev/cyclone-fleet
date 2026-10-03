package com.cyclone.mobile.secrets

import android.content.Context
import com.cyclone.mobile.PhoneToolExecutor

internal class PhoneToolSecretFillExecutor(
    private val context: Context,
) : SecretFillExecutor {
    override fun fill(target: SecretFillTarget, secret: CharArray): SecretFillExecution =
        PhoneToolExecutor.fillVaultSecretOnce(context, target, secret)
}
