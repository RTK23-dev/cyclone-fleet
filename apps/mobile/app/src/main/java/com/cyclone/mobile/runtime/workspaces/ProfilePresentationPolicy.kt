package com.cyclone.mobile.runtime.workspaces

/** Presentation only: never selects a mutation target or creates registry records. */
object ProfilePresentationPolicy {
    fun visibleUsers(workspaceUsers: Set<Int>, savedUsers: Set<Int>, parentUsers: Set<Int>,
                     processUser: Int, ownerUser: Int?): Set<Int> =
        (workspaceUsers + savedUsers + parentUsers + processUser + listOfNotNull(ownerUser)).filter { it >= 0 }.toSet()

    fun isCurrent(user: Int, verifiedCurrent: Int?): Boolean = verifiedCurrent != null && user == verifiedCurrent

    fun canStartTask(user: Int, processUser: Int, verifiedCurrent: Int?, switching: Boolean): Boolean =
        !switching && user == processUser && isCurrent(user, verifiedCurrent)

    fun foregroundActive(user: Int, processUser: Int, executing: Boolean): Boolean = executing && user == processUser
}
