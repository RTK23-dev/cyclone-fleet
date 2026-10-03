plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cyclone.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cyclone.mobile"
        minSdk = 33
        targetSdk = 35
        versionCode = 247
        versionName = "5.0.0-alpha.102.dev1"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }
    buildTypes {
        release {
            // Ordinary CI intentionally produces an unsigned release candidate. Personal-development
            // publishing signs the verified CI artifact separately with the update-compatible dev key.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        // AGP 8.7's bundled Compose/Lifecycle lint detectors crash against the Kotlin 2.2
        // Analysis API required by Kyant Backdrop 1.0.0. Disable only the detectors proven
        // incompatible by CI; all other lint checks remain active. Remove these when Cyclone moves
        // to a lint/AGP line compatible with Kotlin 2.2.x.
        disable += "RememberInComposition"
        disable += "NullSafeMutableLiveData"
        disable += "FrequentlyChangingValue"
        disable += "AutoboxingStateCreation"
    }
}

dependencies {
    implementation(project(":mobilerun-embedded"))
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    // ActivityResult lint requires Fragment 1.3.0+ when a Fragment dependency is present.
    // Pin the current stable release so transitive dependencies cannot drag the app below that floor.
    implementation("androidx.fragment:fragment:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    // Kyant0/AndroidLiquidGlass 1.0.0 keeps the original Android LiquidButton renderer while
    // remaining compatible with Cyclone's AGP line. The optical recipe is used directly.
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("io.github.kyant0:capsule:2.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
