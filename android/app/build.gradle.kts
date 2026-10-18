plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ua.radiomaniac.uvctool"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "ua.radiomaniac.uvctool"
        minSdk = 26
        targetSdk = 35
        // Keep these literal (F-Droid reads them with a regex). The git tag must be
        // v<versionName>; CI refuses to build a tag that does not match.
        versionCode = 404
        versionName = "0.4.4"
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                abiFilters("arm64-v8a")
            }
        }
    }

    // Release signing from the environment (CI secrets or a local shell):
    // UVC_KEYSTORE, UVC_KEYSTORE_PASSWORD, UVC_KEY_ALIAS, UVC_KEY_PASSWORD.
    // Without them the release build is left unsigned.
    val keystore = System.getenv("UVC_KEYSTORE")
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("UVC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UVC_KEY_ALIAS")
                keyPassword = System.getenv("UVC_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
            // In-app updater: checks GitHub releases. F-Droid builds set this false.
            buildConfigField("boolean", "SELF_UPDATE",
                    (System.getenv("UVC_SELF_UPDATE") ?: "true"))
        }
        debug {
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
}
