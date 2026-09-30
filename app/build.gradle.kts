import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autocarplay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.autocarplay"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.0"
    }

    signingConfigs {
        // The release key is private: CI writes it from GitHub Actions secrets and passes its
        // location and password in these environment variables. It is never in the repository.
        // Without them (local builds, pull requests from forks) the release APK is unsigned.
        val keystore = System.getenv("AUTOCARPLAY_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = System.getenv("AUTOCARPLAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AUTOCARPLAY_KEY_ALIAS") ?: "autocarplay"
                keyPassword = System.getenv("AUTOCARPLAY_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

base {
    archivesName.set("AutoCarPlay")
}

dependencies {
    val media3 = "1.8.0"

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")

    // Android for Cars App Library: gives us a drawing surface on the Android Auto screen.
    implementation("androidx.car.app:app:1.7.0")

    // Video playback.
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    testImplementation("junit:junit:4.13.2")
}
