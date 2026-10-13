import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.turingforge.odooclient"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.turingforge.odooclient"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true // BuildConfig.DEBUG / VERSION_NAME se usan en MainActivity
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.1")          // enableEdgeToEdge, OnBackPressedDispatcher, ActivityResult
    implementation("com.google.android.material:material:1.12.0")    // TextInputLayout, diálogos, Snackbar, progress
    implementation("androidx.webkit:webkit:1.13.0")                  // WebViewCompat: addWebMessageListener, addDocumentStartJavaScript
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7") // lifecycleScope
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
