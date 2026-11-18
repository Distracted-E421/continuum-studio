import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * EasyVoice TTS key.
 *
 * Gradle does NOT expose local.properties as project properties (only the
 * Android plugin reads sdk.dir from it), so load it explicitly.
 *
 * Precedence: local.properties, then a -P property, then the env var.
 * local.properties wins deliberately so an ambient EASYVOICE_API_KEY in a shell
 * session cannot silently change which account a build talks to.
 *
 * Empty string disables EasyVoice; the app then falls back to Android TTS.
 */
val easyVoiceApiKey: String = run {
    val localProps = JavaProperties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    localProps.getProperty("EASYVOICE_API_KEY")
        ?: providers.gradleProperty("EASYVOICE_API_KEY").orNull
        ?: System.getenv("EASYVOICE_API_KEY")
        ?: ""
}

android {
    namespace = "com.example.continuumstudio"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.continuumstudio"
        minSdk = 34
        targetSdk = 36
        // versionCode must match OtaUpdateManager.extractVersionCode(tag):
        // major*10000 + minor*100 + patch, or every check reports an "update".
        versionCode = 11941
        versionName = "1.19.41"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Maps API Key - Set via environment or local.properties
        manifestPlaceholders["MAPS_API_KEY"] = project.findProperty("MAPS_API_KEY")?.toString() ?: ""

        buildConfigField("String", "EASYVOICE_API_KEY", "\"$easyVoiceApiKey\"")
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
        buildConfig = true
    }
    
    lint {
        baseline = file("lint-baseline.xml")
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    
    // Networking (WebSocket)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    
    // Architecture
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    
    // DataStore for preferences
    implementation(libs.androidx.datastore.prefs)
    
    // Widget (Glance)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    
    // WorkManager for background tasks
    implementation(libs.androidx.work.runtime.ktx)
    
    // Google Maps
    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    
    // OSMDroid (OpenStreetMap - open source alternative)
    implementation(libs.osmdroid.android)
    
    // Location services
    implementation(libs.play.services.location)
    
    // CameraX for Agent Camera
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    
    // Accompanist (permissions handling)
    implementation(libs.accompanist.permissions)
    
    // Coil (async image loading)
    implementation(libs.coil.compose)

    // Shizuku - lets the app run commands with ADB shell (UID 2000) privileges,
    // which is what makes MultiSound / appops control possible without root.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // Media3 - owning a MediaSession is what stops Android Auto from tearing
    // down the audio route and falling back to the car's own radio.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}