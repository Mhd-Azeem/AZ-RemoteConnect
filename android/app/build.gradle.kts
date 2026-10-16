plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingReady = listOf("AZ_KEYSTORE_PATH", "AZ_KEYSTORE_PASSWORD", "AZ_KEY_ALIAS", "AZ_KEY_PASSWORD")
    .all { !System.getenv(it).isNullOrBlank() }

android {
    namespace = "com.azeem.azremoteconnect"
    compileSdk = 35
    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "com.azeem.azremoteconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
    }

    flavorDimensions += "capability"
    productFlavors {
        create("viewer") {
            dimension = "capability"
            applicationIdSuffix = ".viewer"
            resValue("string", "app_name", "AZ RemoteConnect Viewer")
        }
        create("full") {
            dimension = "capability"
            resValue("string", "app_name", "AZ RemoteConnect")
        }
    }

    signingConfigs {
        if (signingReady) {
            create("secureRelease") {
                storeFile = file(System.getenv("AZ_KEYSTORE_PATH"))
                storePassword = System.getenv("AZ_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AZ_KEY_ALIAS")
                keyPassword = System.getenv("AZ_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (signingReady) signingConfig = signingConfigs.getByName("secureRelease")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
