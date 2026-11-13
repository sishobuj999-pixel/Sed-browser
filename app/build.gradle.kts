plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sed.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sed.browser"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "0.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
