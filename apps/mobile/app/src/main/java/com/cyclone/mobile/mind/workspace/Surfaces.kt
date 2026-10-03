package com.cyclone.mobile.mind.workspace

/**
 * Plan 37 §3: which packages are a real app stay and which are only a surface another app opened for a moment (a share
 * sheet, a picker, a permission or sign-in sheet, the keyboard). A surface never opens a stay: picking a photo in
 * Instagram is still "in Instagram".
 */
object Surfaces {
    private val TRANSIENT = setOf(
        "android",
        "com.android.intentresolver",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.google.android.photopicker",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.credentialmanager",
        "com.google.android.captiveportallogin",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.cyclone.mobile",
    )
    private val KEYBOARDS = listOf("com.google.android.inputmethod", "com.samsung.android.honeyboard", "com.touchtype.swiftkey",
        "com.android.inputmethod", "com.sec.android.inputmethod")
    private val LAUNCHERS = setOf("com.google.android.apps.nexuslauncher", "com.sec.android.app.launcher", "com.miui.home",
        "com.android.launcher", "com.android.launcher3", "com.oneplus.launcher", "com.huawei.android.launcher", "com.teslacoilsw.launcher",
        "com.microsoft.launcher", "bitpit.launcher", "com.nothing.launcher")
    /** Browsers a tap inside an app opens as a Custom Tab: nested in the app's stay unless the mission went there on purpose. */
    private val BROWSERS = setOf("com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.microsoft.emmx",
        "com.brave.browser", "com.opera.browser", "com.duckduckgo.mobile.android")

    fun transient(packageName: String): Boolean =
        packageName.isBlank() || packageName in TRANSIENT || KEYBOARDS.any { packageName.startsWith(it) } || packageName.endsWith(".inputmethod")

    fun launcher(packageName: String): Boolean = packageName in LAUNCHERS || packageName.endsWith(".launcher") || packageName.contains(".launcher.")

    fun browser(packageName: String): Boolean = packageName in BROWSERS
}
