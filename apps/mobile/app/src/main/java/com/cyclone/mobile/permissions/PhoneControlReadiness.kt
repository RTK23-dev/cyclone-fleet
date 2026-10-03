package com.cyclone.mobile.permissions

enum class PhoneControlState { READY, ENABLE, REPAIR }

data class PhoneControlSnapshot(
    val settingEnabled: Boolean,
    val serviceBound: Boolean,
) {
    val state: PhoneControlState get() = PhoneControlReadiness.resolve(settingEnabled, serviceBound)
    val ready: Boolean get() = state == PhoneControlState.READY
    val needsRepair: Boolean get() = state == PhoneControlState.REPAIR
    val actionLabel: String get() = when (state) {
        PhoneControlState.READY -> "Manage"
        PhoneControlState.ENABLE -> "Enable"
        PhoneControlState.REPAIR -> "Repair"
    }
    val statusLabel: String get() = when (state) {
        PhoneControlState.READY -> "Ready"
        PhoneControlState.ENABLE -> "Accessibility off"
        PhoneControlState.REPAIR -> "Needs repair"
    }
    val detail: String get() = when (state) {
        PhoneControlState.READY -> "Phone control matches Android Accessibility."
        PhoneControlState.ENABLE -> "Turn on Cyclone in Android Accessibility, then return."
        PhoneControlState.REPAIR -> "Android still lists Cyclone, but phone control is not running. Re-enable Cyclone in Accessibility after force-stop."
    }
}

object PhoneControlReadiness {
    fun resolve(settingEnabled: Boolean, serviceBound: Boolean): PhoneControlState = when {
        settingEnabled && serviceBound -> PhoneControlState.READY
        settingEnabled && !serviceBound -> PhoneControlState.REPAIR
        else -> PhoneControlState.ENABLE
    }
}
