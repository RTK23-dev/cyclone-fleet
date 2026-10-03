package com.cyclone.mobile.agent.nav

import com.cyclone.mobile.agent.contract.AgentPageCard
import com.cyclone.mobile.applearner.ActionRisk
import com.cyclone.mobile.applearner.PageControl
import com.cyclone.mobile.brain.graphv2.AtlasPersona
import com.cyclone.mobile.gateway.GatewayObservation
import com.cyclone.mobile.gateway.GatewayObservationAdapter
import com.cyclone.mobile.mapping.crawl.MappingStructuralProjection
import com.cyclone.mobile.mapping.crawl.StructuralRoomClassifier
import com.cyclone.mobile.mapping.crawl.fromGateway
import com.cyclone.mobile.places.PlaceResolver
import org.json.JSONObject

internal object LiveNavigationScreen {
    fun from(capture: GatewayObservation, card: AgentPageCard, ledger: TaskLedger): NavigationScreen? {
        if (!card.actionable || capture.id != card.observationId || capture.execution.sessionId != card.sessionId ||
            capture.execution.displayId != card.displayId) return null
        // Mapping passes (and captures whose producer is unknown) never feed live facts, clause proof or people.
        if (capture.persona != AtlasPersona.LIVE) return null
        // Raw entries are already sanitized. Include read-only labels omitted from the action shortlist.
        val raw = capture.elements.values.filter { it.id.startsWith("raw:") &&
            it.evidence.optBoolean("visibleToUser", true) && !it.evidence.optBoolean("password") }
        val page = capture.page.copy(controls = raw.map { element ->
            PageControl(element.id, element.label, element.semanticName, element.role,
                JSONObject(element.evidence.toString()), emptyList(), ActionRisk.SAFE)
        })
        val email = ledger.get("signed-in-email")?.value
        val matched = email != null && capture.elements.values.any { element ->
            val signal = element.label + " " + element.semanticName + " " + element.evidence.optString("resourceId")
            element.evidence.optBoolean("editable") && Regex("(?i)e-?mail").containsMatchIn(signal) &&
                GatewayObservationAdapter.matchesObservedEmail(capture, element.id, email)
        }
        return NavigationScreen(PlaceResolver.resolveCurrent(card)?.id,
            StructuralRoomClassifier.nodeKey(MappingStructuralProjection.fromGateway(capture)), page,
            capture.id, capture.capturedAt, persona = capture.persona, signupEmailMatches = matched &&
                page.controls.any { Regex("(?i)sign[- ]?up|create.*account|register").containsMatchIn(it.label) })
    }
}
