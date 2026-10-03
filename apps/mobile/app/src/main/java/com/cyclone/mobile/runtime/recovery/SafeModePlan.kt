package com.cyclone.mobile.runtime.recovery

import com.cyclone.mobile.platform.module.ModuleId

data class SafeModePlan(
    val launcherComponents: List<String>,
    val trustedCore: Set<TrustedCoreService>,
    val disabledOptionalModules: Set<ModuleId>,
    val preserveUserData: Boolean,
    val allowsAutomaticDataErase: Boolean,
) {
    init {
        require(launcherComponents == listOf(CYCLONE_LAUNCHER)) {
            "Safe Mode must use Cyclone's one existing launcher"
        }
        require(trustedCore == REQUIRED_TRUSTED_CORE) { "Safe Mode trusted core is incomplete" }
        require(preserveUserData) { "Safe Mode must preserve user data" }
        require(!allowsAutomaticDataErase) { "Safe Mode cannot automatically erase user data" }
    }

    fun normalized() = copy(
        launcherComponents = launcherComponents.toList(),
        trustedCore = trustedCore.toSortedSet(),
        disabledOptionalModules = disabledOptionalModules.toSortedSet(),
    )

    companion object {
        const val CYCLONE_LAUNCHER = "com.cyclone.mobile/.MainActivity"
        val REQUIRED_TRUSTED_CORE = TrustedCoreService.entries.toSet()

        fun forSnapshot(snapshot: RecoverySnapshot): SafeModePlan = SafeModePlan(
            launcherComponents = listOf(CYCLONE_LAUNCHER),
            trustedCore = REQUIRED_TRUSTED_CORE,
            disabledOptionalModules = snapshot.modules.filter { !it.essential }.map { it.moduleId }.toSet(),
            preserveUserData = true,
            allowsAutomaticDataErase = false,
        )
    }
}
