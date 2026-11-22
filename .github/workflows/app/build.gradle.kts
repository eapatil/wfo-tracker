plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offline.wfo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offline.wfo"
        minSdk = 26
        targetSdk = 35
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "1.$runNumber"
    }

    signingConfigs {
        create("ciSigning") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("ciSigning")
            }
        }
        release {
            isMinifyEnabled = false
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrEmpty() && file(ksPath).exists()) {
                signingConfig = signingConfigs.getByName("ciSigning")
            }
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

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
