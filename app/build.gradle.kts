plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.openclaw.native_app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openclaw.native_app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Build only for ARM64 (our target device)
        ndk {
            abiFilters += setOf("arm64-v8a")
        }

        // Room schema export
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // Java 17 preferred; falls back to 11 if only JDK 11 available
        val javaVersion = if (System.getProperty("java.version").startsWith("17") ||
                              System.getProperty("java.version").startsWith("21"))
            JavaVersion.VERSION_17 else JavaVersion.VERSION_11
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    kotlinOptions {
        jvmTarget = if (System.getProperty("java.version").startsWith("17") ||
                        System.getProperty("java.version").startsWith("21")) "17" else "11"
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Allow .node native libraries
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Prevent AGP from decompressing .gz assets — store them as-is so
    // AssetManager.open() can find openclaw-bundle.tar.gz by its exact name.
    androidResources {
        noCompress += listOf("gz", "tar", "node")
    }

    // Source sets for generated Room schemas
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // CameraX
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Location
    implementation(libs.play.services.location)

    // Material Components (provides Theme.Material3.DayNight.NoActionBar XML theme)
    implementation("com.google.android.material:material:1.12.0")

    // Network
    implementation(libs.okhttp)
    implementation(libs.gson)

    // DataStore + Security
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)
}
