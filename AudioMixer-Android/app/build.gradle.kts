plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

android {
    namespace = "com.audiomixer.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.audiomixer.app"
        minSdk = 28
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/audiomixer.jks")
            storePassword = "audiomixer"
            keyAlias = "audiomixer"
            keyPassword = "audiomixer"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.antonkarpenko:ffmpeg-kit-audio:2.2.3")
    implementation("de.sciss:jump3r:1.0.5")
}
