plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun quoted(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "network.resilientcomms.meshdemo"
    compileSdk = 37

    defaultConfig {
        applicationId = "network.resilientcomms.meshdemo"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "station"
    productFlavors {
        create("meshA") {
            dimension = "station"
            applicationIdSuffix = ".a"
            versionNameSuffix = "-mesh-a"
            resValue("string", "app_name", "Mesh Demo — Alpha")
            buildConfigField("String", "STATION_NAME", quoted("MESH-ALPHA"))
            buildConfigField("String", "RADIO_NAME", quoted("LT1"))
            buildConfigField("String", "BITCOIN_QUEUE_ID", quoted("alpha"))
        }
        create("meshB") {
            dimension = "station"
            applicationIdSuffix = ".b"
            versionNameSuffix = "-mesh-b"
            resValue("string", "app_name", "Mesh Demo — Bravo")
            buildConfigField("String", "STATION_NAME", quoted("MESH-BRAVO"))
            buildConfigField("String", "RADIO_NAME", quoted("LT2"))
            buildConfigField("String", "BITCOIN_QUEUE_ID", quoted("bravo"))
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
