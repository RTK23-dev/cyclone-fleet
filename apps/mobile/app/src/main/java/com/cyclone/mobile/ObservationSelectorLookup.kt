package com.cyclone.mobile

import com.cyclone.mobile.gateway.GatewayObservationStore
import org.json.JSONObject

object ObservationSelectorLookup {
    fun map(elementIdOrRef: String): ElementSelector? {
        val observation = GatewayObservationStore.current() ?: return ElementSelector(elementId = elementIdOrRef)
        val direct = observation.elements[elementIdOrRef]
        val evidence = direct?.evidence ?: observation.elements.values.firstOrNull { element ->
            element.evidence.optString("ref") == elementIdOrRef
        }?.evidence
        if (evidence == null) return ElementSelector(elementId = elementIdOrRef)
        return fromEvidence(evidence, elementIdOrRef)
    }

    fun mapIndex(elementIndex: Int): ElementSelector? {
        if (elementIndex < 1) return null
        val observation = GatewayObservationStore.current() ?: return null
        val match = observation.elements.values.firstOrNull { element ->
            element.evidence.optInt("elementIndex", element.evidence.optInt("element_index", -1)) == elementIndex
        } ?: return null
        return fromEvidence(match.evidence, match.id)
    }

    fun fromEvidence(evidence: JSONObject, elementId: String): ElementSelector {
        val nested = evidence.optJSONObject("selector") ?: JSONObject()
        fun pick(vararg keys: String): String? {
            for (key in keys) {
                evidence.optString(key).takeIf { it.isNotBlank() }?.let { return it }
                nested.optString(key).takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }
        val nodeText = pick("text")
        val nodeDesc = pick("contentDescription")
        val label = pick("label")
        return ElementSelector(
            resourceId = pick("resourceId"),
            text = nodeText,
            contentDescription = nodeDesc,
            role = pick("role"),
            descendantText = pick("descendantText"),
            fuzzyText = if (nodeText == null && nodeDesc == null) label else null,
            requireClickable = if (evidence.optBoolean("clickable") || nested.optBoolean("clickable")) true else null,
            requireEditable = if (evidence.optBoolean("editable") || nested.optBoolean("editable")) true else null,
            elementId = elementId,
            path = pick("path"),
        )
    }
}
