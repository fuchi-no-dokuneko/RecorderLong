plugins {
    id("com.android.application")
}

val applicationProject = project
val releaseStoreFileProvider = providers.gradleProperty("RELEASE_STORE_FILE")
val releaseStorePasswordProvider = providers.gradleProperty("RELEASE_STORE_PASSWORD")
val releaseKeyAliasProvider = providers.gradleProperty("RELEASE_KEY_ALIAS")
val releaseKeyPasswordProvider = providers.gradleProperty("RELEASE_KEY_PASSWORD")

android {
    namespace = "dev.recorderlong"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.recorderlong"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
                storePassword = releaseStorePasswordProvider.orNull
                keyAlias = releaseKeyAliasProvider.orNull
                keyPassword = releaseKeyPasswordProvider.orNull
            }
        }
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
        }
        release {
            if (releaseStoreFileProvider.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { task ->
        task.project == applicationProject && task.name.contains("Release", ignoreCase = true)
    }
    if (releaseRequested) {
        val signingValues = mapOf(
            "RELEASE_STORE_FILE" to releaseStoreFileProvider.orNull,
            "RELEASE_STORE_PASSWORD" to releaseStorePasswordProvider.orNull,
            "RELEASE_KEY_ALIAS" to releaseKeyAliasProvider.orNull,
            "RELEASE_KEY_PASSWORD" to releaseKeyPasswordProvider.orNull,
        )
        val missing = signingValues.filterValues { it.isNullOrBlank() }.keys
        if (missing.isNotEmpty()) {
            throw GradleException("Release signing properties are required: ${missing.sorted().joinToString()}")
        }
        val storeFile = file(requireNotNull(signingValues.getValue("RELEASE_STORE_FILE")))
        if (!storeFile.isFile) throw GradleException("Release keystore does not exist: $storeFile")
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
