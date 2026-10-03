package com.cyclone.mobile.ai

import com.cyclone.mobile.PhoneSettingsPages
import com.cyclone.mobile.PhoneToolRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmitTextPolicyTest {
    private fun field(resourceId: String, text: String = "") =
        JSONObject().put("selector", JSONObject().put("resourceId", resourceId).put("text", text))

    @Test fun enterInSearchAndAddressFieldsIsFree() {
        listOf("com.android.chrome:id/url_bar", "com.google.android.youtube:id/search_edit_text", "app:id/zoekveld").forEach {
            assertTrue(it, CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.BALANCED, "phone.submit_text", field(it)).allowed)
        }
    }

    @Test fun enterInOtherFieldsNeedsTheOwner() {
        val decision = CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.FULL, "phone.submit_text", field("com.whatsapp:id/entry"))
        assertFalse(decision.allowed)
        assertEquals("LOCAL_CONFIRMATION_REQUIRED", decision.reasonCode)
    }

    @Test fun guidedProfileNeverPressesEnter() {
        assertFalse(CycloneAiAccessPolicy.evaluate(CycloneAiAccessProfile.GUIDED, "phone.submit_text", field("app:id/search")).allowed)
    }

    @Test fun newToolsAreRegisteredAsMutating() {
        assertTrue(PhoneToolRegistry.isMutating("phone.open_settings"))
        assertTrue(PhoneToolRegistry.isMutating("phone.submit_text"))
    }

    @Test fun settingsPagesAreAnAllowlist() {
        assertNotNull(PhoneSettingsPages.page("WiFi".replace("F", "f")))
        assertNull(PhoneSettingsPages.page("android.settings.SETTINGS"))
        assertTrue(PhoneSettingsPages.pages.values.all { it.action.startsWith("android.settings.") })
        assertTrue(PhoneSettingsPages.validPackage("com.google.android.gm"))
        assertFalse(PhoneSettingsPages.validPackage("com.x; rm -rf"))
    }
}
