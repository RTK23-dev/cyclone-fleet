plugins {
    // Kyant0 Backdrop 1.0.0 publishes Kotlin 2.2.x metadata. Keep AGP on Cyclone 4.4.2's
    // established line and align Kotlin + the Compose compiler plugin only.
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
}
