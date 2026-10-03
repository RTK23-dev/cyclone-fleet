package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CycloneProfileProvisioningUiTest {
    @Test fun profileFailureIsHeadlineReasonAndOneSetupAction() {
        val page = source("CycloneProfilesPage.kt")
        assertTrue(page.contains("ProfileSetupRuntime.state.collectAsState()"))
        assertTrue(page.contains("profileSetup.issue"))
        assertTrue(page.contains("Text(issue.headline"))
        assertTrue(page.contains("Text(issue.reason"))
        assertTrue(page.contains("Text(issue.action)"))
        assertTrue(page.contains("Button(onClick = { setup = true }"))
    }

    @Test fun profileErrorUiDoesNotMisrouteRootFailuresToShizuku() {
        val page = source("CycloneProfilesPage.kt")
        assertFalse(page.contains("authorize Shizuku", ignoreCase = true))
        assertFalse(page.contains("Shizuku permission", ignoreCase = true))
    }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
