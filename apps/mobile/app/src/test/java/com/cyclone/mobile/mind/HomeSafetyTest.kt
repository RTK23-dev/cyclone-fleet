package com.cyclone.mobile.mind

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSafetyTest {
    private val launcher = "com.google.android.apps.nexuslauncher"

    @Test fun `a banking icon on the home screen is refused unless the owner named it`() {
        assertNotNull(HomeSafety.refusal(launcher, "bunq", "turn on the flashlight"))
        assertNotNull(HomeSafety.refusal(launcher, "Google Authenticator", "open WhatsApp"))
        assertNotNull(HomeSafety.refusal(launcher, "Proton Authenticator", "set a timer"))
        assertNull(HomeSafety.refusal(launcher, "bunq", "check my balance in bunq"))
        assertNull(HomeSafety.refusal(launcher, "Rabobank", "open my bank app\nyes, Rabobank"))
        assertNull(HomeSafety.refusal(launcher, "ING Bankieren", "open the bank"))
    }

    @Test fun `ordinary apps and other screens are not affected`() {
        assertNull(HomeSafety.refusal(launcher, "WhatsApp", "anything"))
        assertNull(HomeSafety.refusal(launcher, "Settings", "anything"))
        assertNull(HomeSafety.refusal(launcher, "Bingo Blitz", "anything"))
        assertNull(HomeSafety.refusal("com.android.settings", "Banking apps", "turn on dark mode"))
        assertNull(HomeSafety.refusal(null, "bunq", "x"))
    }
}
