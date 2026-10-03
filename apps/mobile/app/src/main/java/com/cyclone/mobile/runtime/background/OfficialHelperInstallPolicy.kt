package com.cyclone.mobile.runtime.background

/** Pins the in-app Shizuku APK and forbids Play Store / market dead-ends. Back is never gated. */
object OfficialHelperInstallPolicy {
    const val PACKAGE = "moe.shizuku.privileged.api"
    const val VERSION = "13.6.0"
    const val URL = "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
    const val SHA256 = "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"

    init {
        require(isPinnedOfficialUrl(URL))
        require(!isForbiddenStoreUrl(URL))
        require(SHA256.length == 64 && SHA256.all { it in '0'..'9' || it in 'a'..'f' })
    }

    fun isForbiddenStoreUrl(url: String): Boolean {
        val lower = url.lowercase()
        val playHost = "play." + "google.com"
        return playHost in lower || lower.startsWith("market:") || "$playHost/store" in lower
    }

    fun isPinnedOfficialUrl(url: String): Boolean =
        url.startsWith("https://github.com/RikkaApps/Shizuku/releases/download/") &&
            url.contains("shizuku-v$VERSION") && url.endsWith(".apk")

    fun backAvailable(phase: String, installFailed: Boolean, userCancelled: Boolean): Boolean = true
}
