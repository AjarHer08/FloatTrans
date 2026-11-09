plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.2.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "3").toInt()

// Optional release signing, injected by CI from repository secrets.
val keystorePath = System.getenv("FLOATTRANS_KEYSTORE_PATH")
val keystorePassword = System.getenv("FLOATTRANS_KEYSTORE_PASSWORD")
val keystoreAlias = System.getenv("FLOATTRANS_KEY_ALIAS")
val keystoreKeyPassword = System.getenv("FLOATTRANS_KEY_PASSWORD")
val hasSigning = !keystorePath.isNullOrBlank() &&
    !keystorePassword.isNullOrBlank() &&
    !keystoreAlias.isNullOrBlank() &&
    file(keystorePath).exists()

android {
    namespace = "com.floattrans.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.floattrans.app"
        minSdk = 23
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        resourceConfigurations += listOf("in", "en")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
        }
        getByName("release") {
            // Keep R8 off: the ML Kit + MediaProjection stack is only lightly kept
            // by its consumer rules and the release APK is sideloaded, not shipped
            // through Play, so size is not worth an untested shrinker pass.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
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

    buildFeatures {
        viewBinding = true
    }

    // ML Kit ships native libraries per ABI; splitting keeps a phone download
    // around 25-30 MB instead of ~113 MB. The universal APK is kept as a
    // fallback for emulators and unusual devices.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // On-device OCR (bundled models: works without Google Play Services).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // On-device translation + language identification.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.2")
}
