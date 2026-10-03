package com.cyclone.mobile.runtime.workspaces

enum class RootWizardStep(val title: String, val action: String) {
    ROOT("Check root", "Check root"),
    PROFILES("Multi-profile isolation", "Profiles ready"),
    REGISTER("Register workspaces", "Save workspace"),
    SWITCH("Test switch", "Test switch"),
    LOCK("Mutate lock status", "Done");
    fun next(): RootWizardStep = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
}
