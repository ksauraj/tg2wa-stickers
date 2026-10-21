plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val providerAuthority = "dev.ksauraj.tg2wa.stickercontentprovider"

android {
    namespace = "dev.ksauraj.tg2wa"
    // tdl-coroutines 15.x declares minCompileSdk=37.
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ksauraj.tg2wa"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["contentProviderAuthority"] = providerAuthority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$providerAuthority\"")
    }

    // Release signing comes from CI secrets; without them the release build
    // falls back to the debug key (fine for sideloading, not for updates).
    val ksFile = System.getenv("TG2WA_KEYSTORE")?.let { file(it) }
    signingConfigs {
        if (ksFile != null && ksFile.exists()) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("TG2WA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TG2WA_KEY_ALIAS")
                keyPassword = System.getenv("TG2WA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("dev.g000sha256:tdl-coroutines:15.0.0")
    implementation("com.airbnb.android:lottie:6.7.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
