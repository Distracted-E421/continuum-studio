plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * synapsix-aa-video
 *
 * A deliberately separate app for experimenting with raw-Surface video on an
 * Android Auto head unit. Split out like devix/continuum so that:
 *
 *  - it gets its own package name, so installation and the installer-spoofing
 *    dance never touch the main Continuum app
 *  - breaking it cannot break dialogs or audio
 *  - the NAVIGATION category it needs does not have to justify itself inside
 *    the main app's manifest
 *
 * How video reaches the car: Android Auto's own UI is already an H.264 stream
 * from the phone, so drawing extra frames adds no protocol cost. The supported
 * escape hatch is SurfaceCallback: declare the NAVIGATION category, request
 * NAVIGATION_TEMPLATES + ACCESS_SURFACE, then draw anything (ExoPlayer,
 * Presentation, Canvas) into the Surface the host hands over.
 *
 * Caveat verified against the docs: Car App 1.8/1.9 add media chrome
 * (MediaPlaybackStyle, ACTION_MEDIA_SHOW_PLAYBACK_VIEW) but no video API.
 * Surface access is still NAVIGATION/MapWithContent only.
 */
android {
    namespace = "com.datapunk.aa.video"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.datapunk.aa.video"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Car App Library - templates plus the raw Surface path.
    implementation(libs.car.app)

    // ExoPlayer for the video we push into that Surface.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
}
