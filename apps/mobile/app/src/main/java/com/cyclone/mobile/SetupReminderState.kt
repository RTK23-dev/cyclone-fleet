package com.cyclone.mobile

enum class SetupNeed {
    PHONE_CONTROL,
    NOTIFICATIONS,
    CALENDAR,
    OVERLAY,
    CORE,
}

object SetupReminderState {
    @Volatile var need: SetupNeed? = null
        private set
    @Volatile var message: String? = null
        private set
    @Volatile var requestedAtMs: Long = 0L
        private set

    fun request(need: SetupNeed, message: String) {
        this.need = need
        this.message = message
        this.requestedAtMs = System.currentTimeMillis()
        DeviceState.addLog("Setup reminder: ${need.name.lowercase()}")
    }

    fun clear() {
        need = null
        message = null
        requestedAtMs = 0L
    }
}
