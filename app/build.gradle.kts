plugins {
    id("com.android.application")
}

android {
    namespace = "dev.recorderlong"
    compileSdk = 36
    val releaseStoreFileProvider = providers.gradleProperty("RELEASE_STORE_FILE")

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

    signingConfigs {
        create("release") {
            val releaseStoreFile = releaseStoreFileProvider.orNull
            if (!releaseStoreFile.isNullOrBlank()) {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFileProvider.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}
