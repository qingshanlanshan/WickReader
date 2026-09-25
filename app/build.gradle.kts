import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.qshlab.shelf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.qshlab.shelf"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.4.1"
        // 64-bit ARM covers practically every phone from the last ~8 years; the ML Kit
        // translation engine alone is ~16 MB per architecture.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Store native libraries compressed: the voice engine's .so shrinks the APK by ~14 MB.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildFeatures {
        compose = true
    }

    compileOptions {
        // Required by Readium.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release signing key lives outside git (keystore/ + keystore.properties). Keep a backup:
    // Android only installs updates signed with the same key.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use(::load) }
    }
    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.navigator.media.tts)
    implementation(libs.readium.opds)
    implementation(libs.readium.adapter.pdfium.document)
    implementation(libs.readium.adapter.pdfium.navigator)
    implementation(libs.mlkit.translate)
    implementation(libs.media3.session)
    // On-device neural TTS (k2-fsa/sherpa-onnx, Apache-2.0), trimmed to arm64-v8a.
    implementation(files("libs/sherpa-onnx-1.13.8-arm64.aar"))
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
