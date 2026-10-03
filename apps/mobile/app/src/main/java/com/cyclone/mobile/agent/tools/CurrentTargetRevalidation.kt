package com.cyclone.mobile.agent.tools

import com.cyclone.mobile.gateway.GatewayElement
import com.cyclone.mobile.gateway.GatewayObservation

enum class TargetDrift { MATCHED, MOVED_SAME_IDENTITY, OCCLUDED, DISAPPEARED, AMBIGUOUS, STALE_FRAME, SCOPE_MISMATCH }
data class TargetRevalidation(val status: TargetDrift, val elementId: String? = null)

/**
 * Identity-based re-resolution; the old rectangle never authorizes a new target.
 *
 * One Android accessibility node can be projected as both a semantic control and a raw node.
 * WebViews can also expose nested semantic wrappers for the same logical control. Those duplicate
 * representations must not make an otherwise unique target ambiguous. Distinct sibling targets
 * remain fail-closed.
 */
internal object CurrentTargetRevalidation {
    fun resolve(before: GatewayObservation, after: GatewayObservation, id: String): TargetRevalidation {
        if (before.execution.sessionId != after.execution.sessionId || before.execution.displayId != after.execution.displayId ||
            before.payload.optLong("executionGeneration") != after.payload.optLong("executionGeneration") || before.page.packageName != after.page.packageName ||
            before.payload.optString("activity") != after.payload.optString("activity")) return TargetRevalidation(TargetDrift.SCOPE_MISMATCH)

        val old = before.elements[id] ?: return TargetRevalidation(TargetDrift.STALE_FRAME)
        val resource = old.evidence.optString("resourceId")
        val editable = isEditable(old)
        // A text box's label is its hint or its current text, which changes as it gains focus or content (plan 21).
        // An editable keeps its identity through its resource id or its raw path.
        val editableIdentity = editable && (resource.isNotBlank() || rawPath(old).isNotBlank())
        if ((old.label.isBlank() || old.label == "<redacted>") && !editableIdentity) return TargetRevalidation(TargetDrift.AMBIGUOUS)

        val byLabel = after.elements.values.filter {
            it.role == old.role && it.label == old.label && it.semanticName == old.semanticName &&
                (resource.isBlank() || it.evidence.optString("resourceId") == resource)
        }
        val matches = if (!editableIdentity) byLabel else (byLabel + after.elements.values.filter { candidate ->
            candidate.role == old.role && isEditable(candidate) && !candidate.evidence.optBoolean("password") &&
                if (resource.isNotBlank()) candidate.evidence.optString("resourceId") == resource
                else rawPath(candidate).isNotBlank() && rawPath(candidate) == rawPath(old)
        }).distinctBy { it.id }
        if (matches.isEmpty()) return TargetRevalidation(TargetDrift.DISAPPEARED)

        val target = uniqueLogicalTarget(old, matches)
            ?: return TargetRevalidation(TargetDrift.AMBIGUOUS)

        if (!target.evidence.optBoolean("enabled", true) || !target.evidence.optBoolean("visibleToUser", true))
            return TargetRevalidation(TargetDrift.OCCLUDED)

        val rect = target.evidence.optJSONObject("bounds") ?: return TargetRevalidation(TargetDrift.AMBIGUOUS)
        if (rect.optInt("right") <= rect.optInt("left") || rect.optInt("bottom") <= rect.optInt("top"))
            return TargetRevalidation(TargetDrift.OCCLUDED)

        val overlapsDistinctTarget = after.elements.values.any { other ->
            if (other.id == target.id || !other.evidence.optBoolean("visibleToUser", true) ||
                !other.evidence.optBoolean("enabled", true) || !other.evidence.optBoolean("clickable")) return@any false
            if (sameLogicalControl(target, other)) return@any false
            // Alpha 91: Cyclone's own chrome is never a competing control for something in the app.
            if (isCycloneChrome(other)) return@any false
            // A composer's text box sits inside a clickable input container (and may hold clickable spans): a node
            // nested with an editable target is the same logical control, not a competing one. Siblings stay closed.
            if (isEditable(target) && nestedWith(target, other)) return@any false
            // Alpha 91: a settings row and the switch, checkbox or radio inside it do the same thing; and a nested node
            // that doesn't even cover the point Cyclone would press can't take the press. Refusing these made every
            // ordinary Settings toggle "ambiguous" and pushed the Mind onto coordinate taps.
            if (nestedWith(target, other) && (isCheckable(target) || isCheckable(other) || !coversCenter(other, rect))) return@any false
            val b = other.evidence.optJSONObject("bounds") ?: return@any false
            b.optInt("left") < rect.optInt("right") && b.optInt("right") > rect.optInt("left") &&
                b.optInt("top") < rect.optInt("bottom") && b.optInt("bottom") > rect.optInt("top")
        }
        if (overlapsDistinctTarget) return TargetRevalidation(TargetDrift.AMBIGUOUS)

        return TargetRevalidation(
            if (old.evidence.optJSONObject("bounds")?.toString() == rect.toString()) TargetDrift.MATCHED
            else TargetDrift.MOVED_SAME_IDENTITY,
            target.id,
        )
    }

    private fun uniqueLogicalTarget(old: GatewayElement, matches: List<GatewayElement>): GatewayElement? {
        if (matches.size == 1) return matches.single()

        val oldNodeId = underlyingNodeId(old)
        if (oldNodeId.isNotBlank()) {
            val exactNode = matches.filter { underlyingNodeId(it) == oldNodeId }
            if (exactNode.isNotEmpty() && exactNode.all { sameLogicalControl(exactNode.first(), it) }) {
                return preferredRepresentation(exactNode)
            }
        }

        val oldPath = rawPath(old)
        if (oldPath.isNotBlank()) {
            val exactPath = matches.filter { rawPath(it) == oldPath }
            if (exactPath.isNotEmpty() && exactPath.all { sameLogicalControl(exactPath.first(), it) }) {
                return preferredRepresentation(exactPath)
            }
        }

        val groups = mutableListOf<MutableList<GatewayElement>>()
        matches.forEach { candidate ->
            val group = groups.firstOrNull { sameLogicalControl(it.first(), candidate) }
            if (group == null) groups += mutableListOf(candidate) else group += candidate
        }
        if (groups.size != 1) {
            // Representations of one text box (semantic, supplement, raw) share its node or path even when their labels
            // differ; distinct fields with the same id stay ambiguous.
            if (isEditable(old)) {
                val nodes = matches.map { underlyingNodeId(it).ifBlank { rawPath(it) } }.filter { it.isNotBlank() }.toSet()
                if (nodes.size == 1 && matches.all { underlyingNodeId(it).ifBlank { rawPath(it) }.isNotBlank() }) return preferredRepresentation(matches)
            }
            return null
        }
        return preferredRepresentation(groups.single())
    }

    private fun isCheckable(element: GatewayElement): Boolean =
        element.evidence.optBoolean("checkable") ||
            element.role.lowercase() in setOf("switch", "checkbox", "check_box", "radio", "radio_button", "radiobutton", "toggle", "toggle_button")

    private fun isCycloneChrome(element: GatewayElement): Boolean =
        element.evidence.optString("resourceId").startsWith("com.cyclone.mobile:id/overlay_chrome") || rawPath(element).startsWith("overlay/")

    private fun coversCenter(element: GatewayElement, target: org.json.JSONObject): Boolean {
        val b = element.evidence.optJSONObject("bounds") ?: return true
        val cx = (target.optInt("left") + target.optInt("right")) / 2
        val cy = (target.optInt("top") + target.optInt("bottom")) / 2
        return cx >= b.optInt("left") && cx < b.optInt("right") && cy >= b.optInt("top") && cy < b.optInt("bottom")
    }

    private fun isEditable(element: GatewayElement): Boolean =
        element.evidence.optBoolean("editable") || element.role.lowercase() in setOf("edit_text", "textbox", "edittext", "text_field")

    private fun nestedWith(a: GatewayElement, b: GatewayElement): Boolean {
        val aPath = rawPath(a)
        val bPath = rawPath(b)
        return aPath.isNotBlank() && bPath.isNotBlank() && nestedPath(aPath, bPath)
    }

    private fun preferredRepresentation(elements: List<GatewayElement>): GatewayElement =
        elements.minBy { element ->
            when (element.source) {
                "semantic" -> 0
                "semantic_supplement" -> 1
                else -> 2
            }
        }

    private fun sameLogicalControl(a: GatewayElement, b: GatewayElement): Boolean {
        val aNode = underlyingNodeId(a)
        val bNode = underlyingNodeId(b)
        if (aNode.isNotBlank() && bNode.isNotBlank() && aNode == bNode) return true

        val aPath = rawPath(a)
        val bPath = rawPath(b)
        if (aPath.isBlank() || bPath.isBlank() || !nestedPath(aPath, bPath)) return false

        val sameLabel = a.label.trim().equals(b.label.trim(), ignoreCase = true)
        val sameSemantic = a.semanticName.trim().equals(b.semanticName.trim(), ignoreCase = true)
        val sameRole = a.role.trim().equals(b.role.trim(), ignoreCase = true)
        val aResource = a.evidence.optString("resourceId")
        val bResource = b.evidence.optString("resourceId")
        val resourceCompatible = aResource.isBlank() || bResource.isBlank() || aResource == bResource
        return sameLabel && sameSemantic && sameRole && resourceCompatible
    }

    private fun underlyingNodeId(element: GatewayElement): String =
        element.evidence.optString("rawNodeId").ifBlank {
            if (element.source == "raw_accessibility") element.evidence.optString("id") else ""
        }

    private fun rawPath(element: GatewayElement): String =
        element.evidence.optString("rawPath").ifBlank {
            if (element.source == "raw_accessibility") element.evidence.optString("path") else ""
        }.trim().trimEnd('/')

    private fun nestedPath(a: String, b: String): Boolean =
        a == b || a.startsWith("$b/") || b.startsWith("$a/")
}
