package com.cyclone.mobile.gateway

import android.content.Context
import com.cyclone.mobile.market.InstalledListing
import com.cyclone.mobile.market.MarketConnection
import com.cyclone.mobile.market.MarketError
import com.cyclone.mobile.market.MarketListing
import com.cyclone.mobile.market.MarketRules
import com.cyclone.mobile.market.Marketplace
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cyclone Marketplace over the gateway (`market.catalog` / `market.install` / `market.remove` / `market.run`). Glass
 * shows the phone's store and changes it here; the phone stays the one authority for what is added and what runs.
 */
internal object GatewayV5MarketAdapter {
    /** Seams for JVM tests; production reads the phone. */
    internal var catalog: () -> List<MarketListing> = { Marketplace.ownerSkills(app()); Marketplace.catalog() }
    internal var installed: () -> List<InstalledListing> = { Marketplace.installs(app()).list() }
    internal var apps: () -> Map<String, String> = { Marketplace.installedApps(app()) }
    internal var connections: () -> List<MarketConnection> = { Marketplace.connections(app()) }
    internal var add: (String, Map<String, String>) -> InstalledListing = { id, inputs -> Marketplace.add(app(), id, inputs, "pc") }
    internal var remove: (String) -> Boolean = { Marketplace.remove(app(), it) }
    internal var run: (String, Map<String, String>) -> com.cyclone.mobile.market.RunRefusal? = { id, inputs -> Marketplace.run(app(), id, inputs) }

    @Volatile private var context: Context? = null
    fun install(context: Context) { this.context = context.applicationContext }
    private fun app(): Context = checkNotNull(context) { "market adapter not installed" }

    fun dispatch(op: String, args: JSONObject): JSONObject = when (op) {
        "market.catalog" -> { only(args, emptySet()); catalogJson() }
        "market.install" -> {
            only(args, setOf("id", "inputs"))
            val entry = guard { add(id(args), inputs(args)) }
            JSONObject().put("id", entry.id).put("added", true).put("inputs", JSONObject(entry.inputs as Map<*, *>))
        }
        "market.remove" -> { only(args, setOf("id")); val id = id(args); JSONObject().put("id", id).put("removed", remove(id)) }
        "market.run" -> {
            only(args, setOf("id", "inputs"))
            val id = id(args)
            val refusal = guard { run(id, inputs(args)) }
            if (refusal != null) throw GatewayProtocolException(refusal.code, refusal.message)
            JSONObject().put("id", id).put("started", true)
        }
        else -> throw GatewayProtocolException("UNKNOWN_OPERATION", "Unsupported market operation: $op")
    }

    internal fun catalogJson(): JSONObject {
        val listings = catalog()
        val added = installed().associateBy { it.id }
        val phoneApps = apps()
        val suggestions = MarketRules.suggestions(listings, phoneApps, added.keys)
        return JSONObject()
            .put("listings", JSONArray().also { out ->
                listings.forEach { listing ->
                    val entry = added[listing.id]
                    out.put(listing.toJson()
                        .put("added", entry != null)
                        .put("savedInputs", entry?.let { JSONObject(it.inputs as Map<*, *>) } ?: JSONObject.NULL)
                        .put("runs", entry?.runs ?: 0)
                        .put("lastRunAt", entry?.lastRunAtMs ?: JSONObject.NULL))
                }
            })
            .put("suggestions", JSONArray().also { out -> suggestions.forEach { (listing, reason) -> out.put(JSONObject().put("id", listing.id).put("reason", reason)) } })
            .put("installedCount", added.keys.count { id -> listings.any { it.id == id } })
            .put("connections", JSONArray().also { out ->
                connections().forEach {
                    out.put(JSONObject().put("id", it.id).put("name", it.name).put("glyph", it.glyph)
                        .put("state", it.state.name.lowercase()).put("detail", it.detail.take(160)).put("where", it.where))
                }
            })
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (error: MarketError) {
        throw GatewayProtocolException("INVALID_REQUEST", error.message ?: "That request is not valid.")
    }

    private fun id(args: JSONObject): String {
        val id = (args.opt("id") as? String).orEmpty()
        if (!MarketRules.LISTING_ID.matches(id)) throw GatewayProtocolException("INVALID_REQUEST", "id is malformed.")
        return id
    }

    private fun inputs(args: JSONObject): Map<String, String> {
        if (!args.has("inputs") || args.isNull("inputs")) return emptyMap()
        val json = args.opt("inputs") as? JSONObject ?: throw GatewayProtocolException("INVALID_REQUEST", "inputs must be an object.")
        if (json.length() > 12) throw GatewayProtocolException("INVALID_REQUEST", "Too many inputs.")
        return json.keys().asSequence().associateWith { key ->
            (json.opt(key) as? String) ?: throw GatewayProtocolException("INVALID_REQUEST", "Input values are text.")
        }
    }

    private fun only(args: JSONObject, allowed: Set<String>) {
        if (args.keys().asSequence().any { it !in allowed }) throw GatewayProtocolException("INVALID_REQUEST", "Unexpected market field.")
    }
}
