plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by CI from repository secrets (see .github/workflows/build.yml).
// Without them, release builds fall back to the debug key so the APK is still installable.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "io.hacalsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.hacalsync"
        minSdk = 26          // java.time without desugaring
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = file(signingStoreFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingStoreFile != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
}
