package com.cyclone.mobile.policy

private val POLICY_NAME_PATTERN = Regex("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+")
private val POLICY_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]*")

enum class PolicyDecision {
    ALLOW,
    ALLOW_ONCE,
    ASK,
    DENY,
}

enum class ActionRisk {
    ROUTINE,
    PRIVACY_SENSITIVE,
    AUTHENTICATION,
    FINANCIAL,
    DESTRUCTIVE,
    EXTERNAL_COMMUNICATION,
    SECURITY_CRITICAL,
}

enum class PrincipalKind {
    CYCLONE_CORE,
    AUTOMATION,
    AI_AGENT,
    DELEGATED_AGENT,
    MODULE,
    EXTERNAL_GATEWAY,
}

data class PrincipalRef(
    val id: String,
    val kind: PrincipalKind,
) {
    init {
        require(POLICY_ID_PATTERN.matches(id)) { "Principal id is invalid: $id" }
    }
}

/**
 * Identifies where a claimed authority came from. Only the three user-controlled origins may be
 * used to issue a grant. All remaining origins are evidence, never authority.
 */
enum class AuthorityOrigin(val canIssueGrant: Boolean) {
    DIRECT_USER_MISSION(true),
    STANDING_USER_RULE(true),
    CURRENT_CONFIRMATION(true),
    APP_UI_TEXT(false),
    WEB_CONTENT(false),
    TOOL_OUTPUT(false),
    BRAIN_MEMORY(false),
    MODULE(false),
    AI_REASONING(false),
    SUBAGENT_INSTRUCTION(false),
}

data class AuthorityClaim(
    val origin: AuthorityOrigin,
    /** Opaque evidence reference. It is deliberately never copied to policy audit output. */
    val reference: String,
) {
    init {
        require(reference.isNotBlank()) { "Authority evidence reference must not be blank" }
    }
}

data class PolicyTarget(
    val packageName: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(packageName == null || packageName.isNotBlank()) { "Package name must not be blank" }
        require(targetType == null || targetType.isNotBlank()) { "Target type must not be blank" }
        require(targetId == null || targetId.isNotBlank()) { "Target id must not be blank" }
        require(attributes.keys.none { it.isBlank() }) { "Target attribute names must not be blank" }
    }
}

/**
 * Exact, non-wildcard action boundaries. Null target fields mean the grant does not constrain that
 * dimension. Capabilities must always be explicitly enumerated; `*` is never accepted.
 */
data class ActionScope(
    val capabilities: Set<String>,
    val missionId: String? = null,
    val packageName: String? = null,
    val targetType: String? = null,
    val targetId: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(capabilities.isNotEmpty()) { "A policy scope needs at least one capability" }
        require(capabilities.all(POLICY_NAME_PATTERN::matches)) {
            "Policy capabilities must be explicit namespaced identifiers"
        }
        require(capabilities.none { '*' in it }) { "Wildcard capabilities are not permitted" }
        require(missionId == null || missionId.isNotBlank()) { "Mission id must not be blank" }
        require(packageName == null || packageName.isNotBlank()) { "Package name must not be blank" }
        require(targetType == null || targetType.isNotBlank()) { "Target type must not be blank" }
        require(targetId == null || targetId.isNotBlank()) { "Target id must not be blank" }
        require(attributes.keys.none { it.isBlank() }) { "Scope attribute names must not be blank" }
    }

    fun contains(request: PolicyRequest): Boolean {
        if (request.capability !in capabilities) return false
        if (missionId != null && request.missionId != missionId) return false
        val target = request.target
        if (packageName != null && target?.packageName != packageName) return false
        if (targetType != null && target?.targetType != targetType) return false
        if (targetId != null && target?.targetId != targetId) return false
        return attributes.all { (key, value) -> target?.attributes?.get(key) == value }
    }

    /** Returns true when [child] is equal to or narrower than this scope. */
    fun contains(child: ActionScope): Boolean =
        capabilities.containsAll(child.capabilities) &&
            constraintContains(missionId, child.missionId) &&
            constraintContains(packageName, child.packageName) &&
            constraintContains(targetType, child.targetType) &&
            constraintContains(targetId, child.targetId) &&
            attributes.all { (key, value) -> child.attributes[key] == value }

    fun isBoundedStandingScope(): Boolean =
        missionId != null || packageName != null || targetType != null || targetId != null || attributes.isNotEmpty()

    private fun constraintContains(parent: String?, child: String?): Boolean = parent == null || parent == child
}

data class DelegationLink(
    val delegator: PrincipalRef,
    val delegate: PrincipalRef,
    val scope: ActionScope,
) {
    init {
        require(delegator != delegate) { "A principal cannot delegate to itself" }
    }
}

data class DelegationChain(val links: List<DelegationLink> = emptyList())

data class PolicyPrincipal(
    val acting: PrincipalRef,
    val delegation: DelegationChain = DelegationChain(),
)

/**
 * A proposed action. Authority claims are intentionally separate from context evidence: untrusted
 * data placed in [authorityClaims] is rejected, while the same data may safely remain evidence.
 */
data class PolicyRequest(
    val actionId: String,
    val capability: String,
    val risk: ActionRisk,
    val principal: PolicyPrincipal,
    val requestedAtEpochMillis: Long,
    val missionId: String? = null,
    val target: PolicyTarget? = null,
    val grantId: String? = null,
    val authorityClaims: List<AuthorityClaim> = emptyList(),
    val contextEvidence: List<AuthorityClaim> = emptyList(),
) {
    init {
        require(POLICY_ID_PATTERN.matches(actionId)) { "Action id is invalid: $actionId" }
        require(POLICY_NAME_PATTERN.matches(capability)) { "Capability must be namespaced: $capability" }
        require(requestedAtEpochMillis >= 0) { "Request timestamp must be non-negative" }
        require(missionId == null || missionId.isNotBlank()) { "Mission id must not be blank" }
        require(grantId == null || POLICY_ID_PATTERN.matches(grantId)) { "Grant id is invalid" }
    }
}
