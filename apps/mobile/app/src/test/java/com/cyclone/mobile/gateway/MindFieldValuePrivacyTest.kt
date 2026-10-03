package com.cyclone.mobile.gateway

import com.cyclone.mobile.applearner.PageContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MindFieldValuePrivacyTest {
    private val observation = GatewayObservation("obs", 100, PageContext("p", "com.example", "A", "T", "s", "c", emptyList(), 1, 100, 100),
        JSONObject().put("semanticControls", JSONArray().put(JSONObject().put("elementId", "semantic:obs:email").put("label", "Email"))),
        emptyMap(), fieldValues = mapOf("semantic:obs:email" to "draft message for Sam"))

    @Test fun valuesStayProcessLocal() {
        assertFalse(observation.toString().contains("draft message"))
        assertFalse(observation.payload.toString().contains("draft message"))
        assertFalse(GatewayObservationAdapter.controls(observation).toString().contains("draft message"))
        assertEquals("draft message for Sam", GatewayObservationAdapter.fieldValue(observation, "semantic:obs:email"))
        assertNull(GatewayObservationAdapter.fieldValue(observation, "semantic:obs:other"))
    }
}
