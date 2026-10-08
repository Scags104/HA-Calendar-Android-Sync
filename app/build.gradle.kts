plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by CI from repository secrets (see .github/workflows/build.yml).
// Without them, release builds fall back to the debug key so the APK is still installable.
val signingStoreFile: String? = System.getenv("SIGNING_STORE_FILE")

// Release builds (tag pushes) use the tag as the version name, e.g. v2026.10.3 -> "2026.10.3",
// so Obtainium sees the installed version match the release. Other builds show "dev.<run>".
// versionCode uses the always-increasing run number so every build installs as an upgrade.
val runNumber: String = System.getenv("GITHUB_RUN_NUMBER") ?: "1"
val releaseTag: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }

android {
    namespace = "io.hacalsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.hacalsync"
        minSdk = 26          // java.time without desugaring
        targetSdk = 34
        versionCode = runNumber.toInt()
        versionName = releaseTag?.removePrefix("v") ?: "dev.$runNumber"
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
