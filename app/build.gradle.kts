plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arjun.viewall"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.arjun.viewall"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The release keystore is intentionally not in the repo. Contributors without
    // it get an unsigned release build; debug builds always work.
    val releaseKeystore = rootProject.file("keystore/viewall.jks")
    if (releaseKeystore.exists()) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = "viewall-local"
                keyAlias = "viewall"
                keyPassword = "viewall-local"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Per-ABI APKs: Pdfium's native libs are the bulk of the size, and a phone
    // only needs its own architecture. The universal APK is still produced.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.12.1")
    // Zoomable image view that tiles huge bitmaps
    implementation("com.davemorrissey.labs:subsampling-scale-image-view-androidx:3.10.0")
    // Pdfium based PDF viewer (maintained fork of barteksc/AndroidPdfViewer)
    implementation("com.github.mhiew:android-pdf-viewer:3.2.0-beta.3")
}
