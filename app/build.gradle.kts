plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.toyremote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.toyremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        // Test builds (gradle -PtestMode=true) simulate the toy so the
        // Telegram side can be tried on a virtual phone. Normal builds don't.
        buildConfigField("boolean", "TEST_MODE", (project.findProperty("testMode") == "true").toString())
    }
    buildFeatures {
        buildConfig = true
    }

    // On GitHub, sign with the fixed key from the DEBUG_KEYSTORE_B64 secret
    // (the build workflow saves it and sets TOYREMOTE_KEYSTORE), so every
    // build has the same signature and updates install over older versions.
    signingConfigs {
        getByName("debug") {
            System.getenv("TOYREMOTE_KEYSTORE")?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
