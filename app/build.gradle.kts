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

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/viewall.jks")
            storePassword = "viewall-local"
            keyAlias = "viewall"
            keyPassword = "viewall-local"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
