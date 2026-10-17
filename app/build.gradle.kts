import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.damou.tigertouch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.damou.tigertouch"
        // Android 7.0: the Galaxy Tab 3 10.1 under LineageOS 14.1 is the reference device.
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // The release key lives outside the repository; keystore.properties (git-ignored) says where.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // A release build: Android shows no "native libraries" warning for it (that one is for debuggable apps).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // HTTP and WebSocket (Creality, Klipper, OctoPrint, Prusa, Snapmaker, Elegoo SDCP, FlashForge).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // MQTT (Bambu Lab, Elegoo CC2, Anycubic): the plain Java client, no Android service.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // The pairing QR code.
    implementation("com.google.zxing:core:3.5.3")
}
