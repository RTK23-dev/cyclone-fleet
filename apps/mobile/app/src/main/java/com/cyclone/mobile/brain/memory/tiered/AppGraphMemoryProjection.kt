package com.cyclone.mobile.brain.memory.tiered

import com.cyclone.mobile.brain.memory.api.MemoryClass
import com.cyclone.mobile.brain.memory.api.MemoryContent
import com.cyclone.mobile.brain.memory.api.MemoryRecord

data class AppGraphMemoryReference(
    val reference: String,
    val projectionType: String,
    val summary: String? = null,
) {
    init {
        require(reference.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*"))) {
            "App Graph reference must be opaque"
        }
        require(projectionType.matches(Regex("[a-z][a-z0-9_.-]*"))) {
            "App Graph projection type is invalid"
        }
    }

    fun toMemoryContent(): MemoryContent = MemoryContent(
        buildMap {
            put("authority", "app_graph")
            put("reference", reference)
            put("projection_type", projectionType)
            summary?.takeIf(String::isNotBlank)?.let { put("summary", it) }
        },
    )
}

object AppGraphProjectionPolicy {
    private val forbiddenBlobFields = setOf(
        "graph", "graph_blob", "raw_graph", "nodes", "edges", "nodes_json", "edges_json",
        "full_snapshot", "database_dump",
    )

    /** Returns a stable failure code, or null when the record is a safe reference/projection. */
    fun validate(record: MemoryRecord): String? {
        if (record.memoryClass != MemoryClass.STRUCTURAL_KNOWLEDGE) return null
        val fromAppGraph = record.provenance.sourceSystem.startsWith("app.graph") ||
            record.content.fields["authority"].equals("app_graph", ignoreCase = true)
        if (!fromAppGraph) return null
        if (record.content.fields.keys.any { it.lowercase() in forbiddenBlobFields }) {
            return "APP_GRAPH_BLOB_NOT_ALLOWED"
        }
        if (record.content.fields["authority"] != "app_graph" ||
            record.content.fields["reference"].isNullOrBlank()
        ) {
            return "APP_GRAPH_REFERENCE_REQUIRED"
        }
        return null
    }
}
