package com.cyclone.mobile.gateway

import com.cyclone.mobile.market.ConnectionState
import com.cyclone.mobile.market.InstalledListing
import com.cyclone.mobile.market.MarketCatalog
import com.cyclone.mobile.market.MarketConnection
import com.cyclone.mobile.market.RunRefusal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayV5MarketAdapterTest {
    private val added = mutableMapOf<String, InstalledListing>()
    private val runs = mutableListOf<Pair<String, Map<String, String>>>()
    private var refusal: RunRefusal? = null

    @Before fun seams() {
        GatewayV5MarketAdapter.catalog = { MarketCatalog.LISTINGS }
        GatewayV5MarketAdapter.installed = { added.values.toList() }
        GatewayV5MarketAdapter.apps = { mapOf("com.whatsapp" to "WhatsApp") }
        GatewayV5MarketAdapter.connections = { listOf(MarketConnection("openrouter", "OpenRouter", "✦", ConnectionState.CONNECTED, "Thinking with x/y", "phone")) }
        GatewayV5MarketAdapter.add = { id, inputs -> InstalledListing(id, "1.0.0", inputs, 1, "pc").also { added[id] = it } }
        GatewayV5MarketAdapter.remove = { added.remove(it) != null }
        GatewayV5MarketAdapter.run = { id, inputs -> runs += id to inputs; refusal }
    }

    private fun code(block: () -> Unit) = (runCatching(block).exceptionOrNull() as GatewayProtocolException).code

    @Test fun theCatalogShowsListingsSuggestionsAddedStateAndConnections() {
        GatewayV5MarketAdapter.dispatch("market.install", JSONObject().put("id", "cyclone.focus-timer").put("inputs", JSONObject().put("minutes", "10")))
        val catalog = GatewayV5MarketAdapter.dispatch("market.catalog", JSONObject())
        assertEquals(MarketCatalog.LISTINGS.size, catalog.getJSONArray("listings").length())
        assertEquals(1, catalog.getInt("installedCount"))
        val timer = (0 until catalog.getJSONArray("listings").length()).map { catalog.getJSONArray("listings").getJSONObject(it) }.first { it.getString("id") == "cyclone.focus-timer" }
        assertTrue(timer.getBoolean("added"))
        assertEquals("10", timer.getJSONObject("savedInputs").getString("minutes"))
        assertEquals("Because you use WhatsApp", catalog.getJSONArray("suggestions").getJSONObject(0).getString("reason"))
        assertEquals("connected", catalog.getJSONArray("connections").getJSONObject(0).getString("state"))
        assertFalse("no secret in the catalog", catalog.toString().contains("sk-or-"))
    }

    @Test fun runsAndRemovalsAreTypedAndRefusalsKeepTheirReason() {
        GatewayV5MarketAdapter.dispatch("market.run", JSONObject().put("id", "cyclone.focus-timer"))
        assertEquals("cyclone.focus-timer", runs.single().first)
        refusal = RunRefusal("ASK_BUSY", "busy")
        assertEquals("ASK_BUSY", code { GatewayV5MarketAdapter.dispatch("market.run", JSONObject().put("id", "cyclone.focus-timer")) })
        assertTrue(GatewayV5MarketAdapter.dispatch("market.remove", JSONObject().put("id", "cyclone.x1")).has("removed"))
        listOf(
            "market.catalog" to JSONObject().put("x", 1),
            "market.install" to JSONObject().put("id", "../x"),
            "market.install" to JSONObject().put("id", "cyclone.focus-timer").put("inputs", JSONObject().put("minutes", 5)),
            "market.run" to JSONObject().put("id", "cyclone.focus-timer").put("shell", "id"),
            "market.delete" to JSONObject(),
        ).forEach { (op, args) -> assertTrue(op, code { GatewayV5MarketAdapter.dispatch(op, args) } in setOf("INVALID_REQUEST", "UNKNOWN_OPERATION")) }
    }
}
