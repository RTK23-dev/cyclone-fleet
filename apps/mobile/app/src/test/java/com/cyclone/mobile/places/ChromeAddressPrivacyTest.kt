package com.cyclone.mobile.places

import com.cyclone.mobile.gateway.GatewayPrivacy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChromeAddressPrivacyTest {
    @Test fun addressBarTextAndDescriptionNeverLeaveAsFullUrl() {
        val url = "https://example.com/private?token=topsecret#part"
        val node = JSONObject()
            .put("resourceId", "com.android.chrome:id/url_bar")
            .put("text", url)
            .put("contentDescription", url)
            .put("editable", false)
        val safe = GatewayPrivacy.sanitizeAccessibilitySnapshot(
            JSONObject().put("nodes", JSONArray().put(node)),
        )
        val browserNode = safe.getJSONArray("nodes").getJSONObject(0)
        assertEquals("<redacted>", browserNode.getString("text"))
        assertEquals("<redacted>", browserNode.getString("contentDescription"))
        assertFalse(safe.toString().contains("topsecret"))
        assertFalse((GatewayPrivacy.sanitizeDeep(node) as JSONObject).toString().contains("topsecret"))
    }
}
