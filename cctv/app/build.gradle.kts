plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val versionCodeEnv = (System.getenv("NC_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "ru.nordcam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.nordcam.app"
        minSdk = 26
        targetSdk = 34
        versionCode = versionCodeEnv
        versionName = "1.0.$versionCodeEnv"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("NC_KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("NC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NC_KEY_ALIAS") ?: "nordkey"
                keyPassword = System.getenv("NC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("NC_KEYSTORE_FILE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Складные телефоны: положение сгиба (книжка / ноутбук)
    implementation("androidx.window:window:1.3.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    // Встроенный VPN: WireGuard и AmneziaWG (обфускация от блокировок), совместим с обычными конфигами WireGuard
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Видео-движок: RTSP/RTP, H.264/H.265, MJPEG — работает почти с любыми камерами
    implementation("org.videolan.android:libvlc-all:3.6.0")
}
