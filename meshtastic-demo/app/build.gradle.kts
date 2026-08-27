plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val demoDebugKeystorePath = providers.environmentVariable("DEMO_DEBUG_KEYSTORE_PATH")

android {
    namespace = "network.resilientcomms.meshdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "network.resilientcomms.meshdemo"
        minSdk = 26
        targetSdk = 37
        versionCode = 10
        versionName = "0.4.0-beta.3"
        resValue("string", "app_name", "Meshtastic Conference Demo")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            demoDebugKeystorePath.orNull?.takeIf(String::isNotBlank)?.let { keystorePath ->
                storeFile = file(keystorePath)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("org.meshtastic:sdk-core:0.1.0")
    implementation("org.meshtastic:sdk-transport-ble:0.1.0")
    implementation("org.meshtastic:sdk-storage-sqldelight:0.1.0")
    // sdk-transport-ble 0.1.0 exposes PeripheralBuilder in its public Android factory but
    // does not export Kable transitively, so consumers must align this dependency explicitly.
    implementation("com.juul.kable:kable-core:0.44.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
