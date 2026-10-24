plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Match the NDK the native libs are built with (build_native_android.sh).
    ndkVersion = "28.2.13676358"
    // The namespace stays com.clavierhaus.gnubg: the JNI symbols in the engine
    // bridge are named Java_com_clavierhaus_gnubg_Engine_*.
    namespace = "com.clavierhaus.gnubg"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.myrace.analyze"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The debug key signs release builds too, so the APK installs
            // straight away. Fine for personal use; not for a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
}
