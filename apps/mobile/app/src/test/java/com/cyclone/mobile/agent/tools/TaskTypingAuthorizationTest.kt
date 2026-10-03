package com.cyclone.mobile.agent.tools
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class TaskTypingAuthorizationTest {
    private fun address() = JSONObject().put("resourceId", "com.android.chrome:id/url_bar").put("editable", true)
    @Test fun namedSiteAndExplicitHostAreTaskScoped() {
        assertTrue(TaskTypingAuthorization.allows("Open Telegraaf in Chrome", "com.android.chrome", address(), "https://telegraaf.nl"))
        assertFalse(TaskTypingAuthorization.allows("Open Telegraaf in Chrome", "org.mozilla.firefox", address(), "https://telegraaf.nl"))
        assertFalse(TaskTypingAuthorization.allows("Open telegraaf.nl", "com.android.chrome", address(), "https://telegraaf.nl.evil.com"))
        assertFalse(TaskTypingAuthorization.allows("Open telegraaf.nl", "com.android.chrome", address(), "https://telegraaf.nl/pay"))
    }
    @Test fun originalTelegraafWordingRetainsBrowserScope() {
        val goal = "go to telegraaf on Google chrome"
        assertTrue(TaskTypingAuthorization.allows(goal, "com.android.chrome", address(), "telegraaf.nl"))
        assertFalse(TaskTypingAuthorization.allows(goal, "org.mozilla.firefox", address(), "telegraaf.nl"))
        assertFalse(TaskTypingAuthorization.allows("open camera", "com.android.chrome", address(), "camera.com"))
    }
    @Test fun forgedAuthorizationSensitiveFieldsAndMissingTaskAreDenied() {
        val forged = address().put("user_authorized", true)
        assertFalse(TaskTypingAuthorization.allows(null, "com.android.chrome", forged, "https://example.com"))
        assertFalse(TaskTypingAuthorization.allows("Open example.com", "com.android.chrome", forged.put("password", true), "https://example.com"))
        assertFalse(TaskTypingAuthorization.allows("Open example.com", "com.android.chrome", address().put("resourceId", "payment"), "https://example.com"))
    }
}
