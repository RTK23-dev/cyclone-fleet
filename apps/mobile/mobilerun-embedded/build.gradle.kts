plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

/**
 * Cyclone 3.9.1 keeps this module only for the process-crash journal used by the native Android
 * runtime. The old Mobilerun Portal compatibility stack depended on a separately materialized
 * upstream application tree, duplicated Cyclone services, and made clean builds network-dependent.
 * Native Cyclone Accessibility, notification, overlay, automation, screenshot, typing and gateway
 * code are now authoritative, so this library intentionally has no upstream checkout or runtime UI.
 */
android {
    namespace = "com.mobilerun.portal"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
