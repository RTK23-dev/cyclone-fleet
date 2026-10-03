package com.cyclone.mobile

/**
 * TalkBack-oriented role inference and host folding for accessibility snapshots.
 * Pure functions so JVM unit tests can cover Clock tabs, Files rows, and Calculator digits.
 */
object AccessibilityRoles {
    private val TAB_CLASS = Regex("(?:^|[.$])(tablayout|tabview|tabwidget|tabitem|tabrow|tab)(?:$|[.$])", RegexOption.IGNORE_CASE)
    private val TAB_BAR_CLASS = Regex("tablayout|tabwidget|tabrow|tabbar", RegexOption.IGNORE_CASE)
    private val ROW_CLASS = Regex("preference|listitem|recyclerview|listview", RegexOption.IGNORE_CASE)
    private val HOST_ROLES = setOf("button", "tab", "row", "textbox", "switch", "checkbox")
    private val DATE_OR_SIZE = Regex(
        """(?i)(?:^(?:mon|tue|wed|thu|fri|sat|sun)\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)\b|""" +
            """\d+[.,]?\d*\s*(?:mb|gb|kb|bytes)\b|\b\d+\s+(?:days?|hours?|minutes?|weeks?)\s+ago\b)""",
    )
    private val FILENAME = Regex("""(?i)[\w.-]+\.(apk|zip|pdf|png|jpe?g|txt|mp4|csv|json|html|bin)\b""")

    fun inferRole(
        className: String,
        clickable: Boolean = false,
        editable: Boolean = false,
        checkable: Boolean = false,
        scrollable: Boolean = false,
        selected: Boolean = false,
        text: String = "",
        contentDescription: String = "",
        resourceId: String = "",
        parentClassName: String = "",
        actions: List<String> = emptyList(),
    ): String {
        val cls = className.lowercase()
        val parentCls = parentClassName.lowercase()
        val resource = resourceId.lowercase()
        if (editable || "edittext" in cls) return "textbox"
        if (isTab(cls, resource, parentCls, selected, clickable)) return "tab"
        if ("checkbox" in cls || checkable) return "checkbox"
        if ("switch" in cls) return "switch"
        if (isRow(cls, resource, parentCls)) return "row"
        val labeled = text.isNotBlank() || contentDescription.isNotBlank()
        val canClick = clickable || "ACTION_CLICK" in actions
        if ("button" in cls || (canClick && labeled)) return "button"
        // Busy indicators are evidence for the settle controller ("still loading"), never an action target.
        if ("progressbar" in cls || "progressindicator" in cls) return "progress"
        if ("image" in cls) return "image"
        if (scrollable) return "scroll_container"
        if ("textview" in cls) return "text"
        return "generic"
    }

    fun isTab(
        className: String,
        resourceId: String = "",
        parentClassName: String = "",
        selected: Boolean = false,
        clickable: Boolean = false,
    ): Boolean {
        val cls = className.lowercase()
        if (cls.contains("table") || cls.contains("tablet")) return false
        if (TAB_CLASS.containsMatchIn(cls)) return true
        if (resourceId.lowercase().contains("tab") && !resourceId.lowercase().contains("table")) return true
        val inTabBar = TAB_BAR_CLASS.containsMatchIn(parentClassName)
        return inTabBar && (selected || clickable || className.contains("TextView", ignoreCase = true))
    }

    fun isRow(className: String, resourceId: String = "", parentClassName: String = ""): Boolean {
        return ROW_CLASS.containsMatchIn(className) ||
            ROW_CLASS.containsMatchIn(resourceId) ||
            ROW_CLASS.containsMatchIn(parentClassName)
    }

    fun isHostRole(role: String): Boolean = role.lowercase() in HOST_ROLES

    fun isActivatable(clickable: Boolean, actions: List<String>): Boolean =
        clickable || "ACTION_CLICK" in actions || "ACTION_SELECT" in actions

    fun isStrongActivatable(clickable: Boolean, actions: List<String>): Boolean =
        "ACTION_CLICK" in actions || "ACTION_SELECT" in actions

    fun shouldPreferActivatableRelative(chosen: UiNodeSnapshot): Boolean {
        val role = chosen.role.lowercase()
        if (role == "generic" || role.isBlank()) return true
        if (!isStrongActivatable(chosen.clickable, chosen.actions) && !chosen.editable) return true
        return false
    }

    fun resolveActivationTarget(nodes: List<UiNodeSnapshot>, chosen: UiNodeSnapshot): UiNodeSnapshot {
        if (nodes.isEmpty()) return chosen
        val byId = nodes.associateBy { it.id }
        if (chosen.role.equals("generic", ignoreCase = true) || chosen.role.isBlank()) {
            firstStrongRelative(chosen, byId)?.let { return it }
            val parent = chosen.parentId?.let(byId::get)
            if (parent != null) {
                firstStrongRelative(parent, byId)?.let { if (it.id != chosen.id) return it }
            }
        }
        if (isStrongActivatable(chosen.clickable, chosen.actions) || chosen.editable) return chosen
        var parentId = chosen.parentId
        repeat(6) {
            val parent = parentId?.let(byId::get) ?: return chosen
            if (parent.role.equals("generic", ignoreCase = true) || parent.role.isBlank()) {
                firstStrongRelative(parent, byId)?.let { return it }
            }
            if (isStrongActivatable(parent.clickable, parent.actions) || parent.editable || isHostRole(parent.role)) {
                return parent
            }
            parentId = parent.parentId
        }
        return chosen
    }

    internal fun firstStrongRelative(host: UiNodeSnapshot, byId: Map<String, UiNodeSnapshot>): UiNodeSnapshot? {
        val children = host.childIds.mapNotNull(byId::get)
        val buttons = children.filter { it.role.equals("button", ignoreCase = true) && isStrongActivatable(it.clickable, it.actions) }
        if (buttons.isNotEmpty()) return buttons.first()
        val strong = children.filter { isStrongActivatable(it.clickable, it.actions) }
        if (strong.isNotEmpty()) return strong.first()
        for (child in children) {
            firstStrongRelative(child, byId)?.let { return it }
        }
        return null
    }

    private fun deferGenericHost(parent: UiNodeSnapshot, relative: UiNodeSnapshot): Boolean {
        if (!isStrongActivatable(relative.clickable, relative.actions)) return false
        if (isRow(parent.className, parent.resourceId)) return false
        if (isHostRole(parent.role)) return false
        return parent.role.equals("generic", ignoreCase = true) || parent.role.isBlank()
    }

    fun preferClickableAncestor(role: String, nodeClickable: Boolean, actions: List<String>): Boolean {
        val roleName = role.lowercase()
        if (roleName in setOf("text", "image")) return true
        if (!isActivatable(nodeClickable, actions)) return true
        return roleName == "generic"
    }

    fun isPublishedInteractive(
        visibleToUser: Boolean,
        interactive: Boolean,
        boundsWidth: Int,
        boundsHeight: Int,
    ): Boolean {
        if (visibleToUser) return true
        return interactive && boundsWidth > 0 && boundsHeight > 0
    }

    fun joinHostLabels(parts: List<String>): String {
        val unique = parts.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return ""
        val best = unique.maxByOrNull(::hostLabelScore) ?: return ""
        val rest = unique.filter { it != best }
        return (listOf(best) + rest).joinToString(" ")
    }

    internal fun hostLabelScore(label: String): Int {
        val t = label.trim()
        var score = t.length.coerceAtMost(80)
        if (FILENAME.containsMatchIn(t)) score += 1000
        if (t.contains('.')) score += 80
        if (DATE_OR_SIZE.containsMatchIn(t)) score -= 500
        return score
    }

    fun foldTalkBackHosts(nodes: List<UiNodeSnapshot>): List<UiNodeSnapshot> {
        if (nodes.isEmpty()) return nodes
        val byId = nodes.associateBy { it.id }
        val labels = linkedMapOf<String, MutableList<String>>()
        val roles = linkedMapOf<String, String>()
        val demote = linkedSetOf<String>()
        for (node in nodes) {
            if (node.clickable || node.editable || "ACTION_CLICK" in node.actions) continue
            if (node.role !in setOf("text", "generic", "image", "")) continue
            val ownLabel = node.text.trim().ifBlank { node.contentDescription.trim() }
            if (ownLabel.isBlank()) continue
            var parentId = node.parentId
            repeat(6) {
                val parent = parentId?.let(byId::get) ?: return@repeat
                val relative = firstStrongRelative(parent, byId)
                val attach = when {
                    relative != null && deferGenericHost(parent, relative) -> relative
                    parent.editable || isActivatable(parent.clickable, parent.actions) -> parent
                    else -> null
                }
                if (attach != null) {
                    labels.getOrPut(attach.id) { mutableListOf() }.add(ownLabel)
                    roles[attach.id] = when {
                        isRow(attach.className, attach.resourceId) -> "row"
                        else -> inferRole(
                            className = attach.className,
                            clickable = true,
                            editable = attach.editable,
                            checkable = attach.checkable,
                            scrollable = attach.scrollable,
                            selected = attach.selected,
                            text = attach.text.ifBlank { ownLabel },
                            contentDescription = attach.contentDescription,
                            resourceId = attach.resourceId,
                            actions = attach.actions,
                        )
                    }
                    if (attach.id != parent.id && deferGenericHost(parent, attach)) {
                        demote += parent.id
                    }
                    return@repeat
                }
                parentId = parent.parentId
            }
        }
        if (labels.isEmpty() && roles.isEmpty() && demote.isEmpty()) return nodes
        return nodes.map { node ->
            val promoted = labels[node.id]
            val role = roles[node.id]
            val demoted = node.id in demote && promoted == null
            when {
                demoted -> node.copy(
                    clickable = false,
                    actions = node.actions.filter { it != "ACTION_CLICK" },
                )
                promoted == null && role == null -> node
                else -> node.copy(
                    text = node.text.ifBlank { joinHostLabels(promoted.orEmpty()) },
                    clickable = true,
                    role = role ?: node.role,
                    actions = if ("ACTION_CLICK" in node.actions) node.actions else node.actions + "ACTION_CLICK",
                )
            }
        }
    }
}
