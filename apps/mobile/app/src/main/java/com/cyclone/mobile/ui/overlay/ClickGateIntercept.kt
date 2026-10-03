package com.cyclone.mobile.ui.overlay

import com.cyclone.mobile.ElementSelector
import com.cyclone.mobile.UiNodeSnapshot
import com.cyclone.mobile.policy.GateClass
import com.cyclone.mobile.policy.GateClassifier

/**
 * Intercept host clicks that classify as GATE before Accessibility ACTION_CLICK.
 * Files "Move to bin" must enter overlay GATE from IDLE or LIVE and must not be performed.
 */
class GateBlockedException(
    val gateClass: OverlayGateClass?,
    message: String = "GATE ${gateClass?.wire ?: "required"} requires confirmation",
) : RuntimeException(message)

object ClickGateIntercept {
    data class Decision(
        val performClick: Boolean,
        val enterGate: Boolean,
        val gateClass: OverlayGateClass?,
    )

    fun labelsFor(
        chosen: UiNodeSnapshot,
        activation: UiNodeSnapshot = chosen,
        selector: ElementSelector? = null,
    ): List<String> {
        val labels = mutableListOf<String>()
        fun add(value: String?) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty() && trimmed !in labels) labels += trimmed
        }
        add(chosen.text)
        add(chosen.contentDescription)
        add(activation.text)
        add(activation.contentDescription)
        add(selector?.text)
        add(selector?.textContains)
        add(selector?.contentDescription)
        add(selector?.contentDescriptionContains)
        add(selector?.fuzzyText)
        add(selector?.descendantText)
        return labels
    }

    /**
     * Labels of whatever a coordinate tap at ([x], [y]) would hit: the innermost node under the point (often the
     * text of a button) and the innermost clickable node with its activation target. Used so a tap by position gets
     * exactly the approval check a tap on the labelled control would get.
     */
    fun labelsAtPoint(nodes: List<UiNodeSnapshot>, x: Int, y: Int): List<String> {
        val under = nodes.filter { node ->
            node.visibleToUser && node.bounds.width > 0 && node.bounds.height > 0 &&
                x >= node.bounds.left && x < node.bounds.right && y >= node.bounds.top && y < node.bounds.bottom
        }
        val innermost = under.maxByOrNull { it.depth } ?: return emptyList()
        val clickable = under.filter { it.clickable || it.longClickable }.maxByOrNull { it.depth }
        val labels = labelsFor(innermost).toMutableList()
        clickable?.let { node ->
            val activation = com.cyclone.mobile.AccessibilityRoles.resolveActivationTarget(nodes, node)
            labelsFor(node, activation).forEach { if (it !in labels) labels += it }
            // A clickable row or button often carries its words on its children.
            nodes.filter { it.path.startsWith(node.path) && it.path != node.path }.take(12).forEach { child ->
                labelsFor(child).forEach { if (it !in labels) labels += it }
            }
        }
        return labels
    }

    fun overlayClass(gateClass: GateClass): OverlayGateClass = OverlayGateClass.parse(gateClass.jsonKey)

    fun decide(
        action: String,
        labels: List<String>,
        overlayState: OverlayChromeState,
        useRuntimeApproval: Boolean = true,
    ): Decision {
        val classified = GateClassifier.classify(action, labels) ?: return Decision(
            performClick = true,
            enterGate = false,
            gateClass = null,
        )
        val overlay = overlayClass(classified)
        if (useRuntimeApproval && OverlayChromeRuntime.consumeGateApproval(overlay, action, labels)) {
            return Decision(performClick = true, enterGate = false, gateClass = overlay)
        }
        if (useRuntimeApproval) OverlayChromeRuntime.registerGateChallenge(overlay, action, labels)
        return Decision(
            performClick = false,
            enterGate = overlayState != OverlayChromeState.GATE,
            gateClass = overlay,
        )
    }

    fun apply(
        machine: OverlayChromeMachine,
        action: String,
        labels: List<String>,
        pcAutoApprove: Boolean = false,
    ): Boolean {
        val decision = decide(action, labels, machine.state(), useRuntimeApproval = false)
        if (decision.enterGate && decision.gateClass != null) {
            machine.enterGate(decision.gateClass, pcAutoApprove)
        }
        return decision.performClick
    }
}
