package com.cyclone.mobile.market

import org.json.JSONArray
import java.io.File

/** What the owner has added, in one small JSON file. The phone is the authority; Glass reads and changes it here. */
class MarketInstalls(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()

    fun list(): List<InstalledListing> = synchronized(lock) { read() }

    fun get(id: String): InstalledListing? = list().firstOrNull { it.id == id }

    fun add(listing: MarketListing, inputs: Map<String, String>, source: String): InstalledListing = synchronized(lock) {
        val current = read()
        val kept = current.firstOrNull { it.id == listing.id }
        val entry = InstalledListing(listing.id, listing.version, inputs, kept?.installedAtMs ?: clock(), source, kept?.runs ?: 0, kept?.lastRunAtMs)
        write(current.filterNot { it.id == listing.id } + entry)
        entry
    }

    fun remove(id: String): Boolean = synchronized(lock) {
        val current = read()
        val next = current.filterNot { it.id == id }
        if (next.size != current.size) write(next)
        next.size != current.size
    }

    fun recordRun(id: String) = synchronized(lock) {
        write(read().map { if (it.id == id) it.copy(runs = it.runs + 1, lastRunAtMs = clock()) else it })
    }

    private fun read(): List<InstalledListing> = runCatching {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(InstalledListing::fromJson) }
    }.getOrDefault(emptyList())

    private fun write(items: List<InstalledListing>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray().also { array -> items.forEach { array.put(it.toJson()) } }.toString())
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }
}
