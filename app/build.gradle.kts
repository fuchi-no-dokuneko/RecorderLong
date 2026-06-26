plugins {
    id("com.android.application")
}

android {
    namespace = "dev.recorderlong"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.recorderlong"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
