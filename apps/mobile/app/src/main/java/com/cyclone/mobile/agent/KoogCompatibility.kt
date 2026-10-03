package com.cyclone.mobile.agent

/**
 * Evidence captured for the 3.8.6 base while evaluating direct JetBrains Koog embedding.
 *
 * Cyclone's mobile build is pinned to Kotlin 2.0.21. The current Koog 1.2.0 quickstart requires
 * Kotlin 2.2.0+, so adding Koog directly here would require a root Android toolchain upgrade outside
 * this lane's allowed ownership. CycloneLocalAgent deliberately keeps provider, tools, checkpoints
 * and graph transitions behind Koog-shaped interfaces so a later toolchain upgrade can replace the
 * graph engine without replacing Cyclone's Android execution authority.
 */
object KoogCompatibility {
    const val directKoogEnabled: Boolean = false
    const val repositoryKotlinVersion: String = "2.0.21"
    const val evaluatedKoogVersion: String = "1.2.0"
    const val requiredKotlinVersion: String = "2.2.0+"
    const val blocker: String =
        "Direct Koog embedding requires a Kotlin toolchain upgrade outside the Agent 1 ownership lane."
}
