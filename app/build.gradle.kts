plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release credentials never belong in the repository. Our local release
// process supplies these three values from the developer's secure key store.
val releaseStoreFile = System.getenv("UNSCROLL_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("UNSCROLL_RELEASE_STORE_PASSWORD")
val releaseKeyPassword = System.getenv("UNSCROLL_RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }

// Firebase is activated automatically once the owner adds app/google-services.json.
// Keeping the beta build configuration-safe lets offline focus features continue working.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.nextmove.focus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nextmove.focus"
        minSdk = 23
        targetSdk = 35
        versionCode = 37
        versionName = "0.3.11"
    }

    signingConfigs {
        create("unscrollRelease") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = "unscroll"
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            // Local unsigned builds remain possible, but distributable builds
            // always supply the permanent release identity above.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("unscrollRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(platform("com.google.firebase:firebase-bom:34.18.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}
