import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.amipro.editor"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.amipro.editor"
        minSdk = 28
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        // Only the 64-bit phone ABI is built (build-rust.sh); add x86_64 there for an emulator.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Release signing key lives OUTSIDE the repo (never commit it). Without it, release builds fall back to the debug key.
    val ksProps = Properties().apply {
        val f = file(System.getenv("AMIPRO_KEYSTORE_PROPS") ?: "${System.getProperty("user.home")}/APK-Keys/amipro/keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (ksProps.isNotEmpty()) create("release") {
            storeFile = file(ksProps.getProperty("storeFile")); storePassword = ksProps.getProperty("storePassword")
            keyAlias = ksProps.getProperty("keyAlias"); keyPassword = ksProps.getProperty("keyPassword")
        }
    }
    lint { checkReleaseBuilds = false }
    buildTypes {
        // Debug installs next to a release build (own id, own label).
        debug { applicationIdSuffix = ".debug" }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // UniFFI's generated Kotlin uses JNA to load the Rust library.
    implementation("net.java.dev.jna:jna:5.19.1@aar")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test-junit"))
}
