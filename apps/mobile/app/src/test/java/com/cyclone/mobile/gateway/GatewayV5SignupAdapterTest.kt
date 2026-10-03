package com.cyclone.mobile.gateway

import com.cyclone.mobile.mind.signup.SignupRecorder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 43 T6: the PC reads sign-up maps (schemas only) and can forget one. */
class GatewayV5SignupAdapterTest {
    private fun code(block: () -> Unit): String = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code

    @Test fun mapsGoOutAsSchemasAndCanBeForgotten() {
        val recorder = SignupRecorder("com.instagram.android", "Instagram", "350.0") { 5L }
        recorder.typed("Brand One Studio")
        recorder.page("What's your name?", listOf(mapOf("label" to "Full name", "kind" to "full_name")), "Next", null)
        val map = recorder.finish(complete = false)
        val forgotten = mutableListOf<String>()
        GatewayV5SignupAdapter.maps = { listOf(map) }
        GatewayV5SignupAdapter.forget = { pkg -> forgotten += pkg; true }

        val out = GatewayV5SignupAdapter.dispatch("signup.maps", JSONObject())
        val first = out.getJSONArray("maps").getJSONObject(0)
        assertEquals("com.instagram.android", first.getString("package"))
        assertEquals("full_name", first.getJSONArray("pages").getJSONObject(0).getJSONArray("fields").getJSONObject(0).getString("kind"))
        assertFalse(out.toString().contains("Brand One"))
        assertFalse(out.getBoolean("truncated"))
        assertTrue(GatewayV5SignupAdapter.dispatch("signup.forget", JSONObject().put("package", "com.instagram.android")).getBoolean("forgotten"))
        assertEquals(listOf("com.instagram.android"), forgotten)
        assertEquals("INVALID_REQUEST", code { GatewayV5SignupAdapter.dispatch("signup.maps", JSONObject().put("x", 1)) })
        assertEquals("INVALID_REQUEST", code { GatewayV5SignupAdapter.dispatch("signup.forget", JSONObject().put("package", "../x")) })
        assertTrue(GatewayProtocol.operations.contains("signup.maps") && GatewayProtocol.operations.contains("signup.forget"))
    }
}
