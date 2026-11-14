import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "id.webdev.studio"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "id.webdev.studio"
        minSdk = 26
        // PENTING: targetSdk 28 disengaja. Android 10+ (targetSdk >= 29) memblokir execve()
        // terhadap file di direktori data aplikasi. Node.js/npm menjalankan binary native
        // (esbuild, dll.) dari node_modules, sehingga butuh targetSdk 28 (pendekatan yang sama
        // dengan Termux). Konsekuensi: tidak cocok untuk Play Store, tetapi aman untuk sideload APK.
        targetSdk = 28
        versionCode = 5
        versionName = "0.4.0"
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Binary Node.js dijalankan dari nativeLibraryDir -> harus diekstrak ke disk.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    androidResources {
        // node-runtime.tar.xz sudah terkompresi (xz); simpan apa adanya agar APK cepat dibangun/dipasang.
        noCompress += listOf("zip", "xz")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // Runtime Node.js opsional (lihat README, bagian "Runtime Node.js").
    // Folder ini diisi oleh CI / script; bila kosong, build tetap berhasil.
    sourceSets["main"].jniLibs.srcDir("../runtime-bundle/jniLibs")
    sourceSets["main"].assets.srcDir("../runtime-bundle/assets")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.tukaani:xz:1.10")
}
