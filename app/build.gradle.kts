plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("com.google.dagger.hilt.android")
}

android {
    namespace = "zaujaani.vibra"
    compileSdk = 34

    defaultConfig {
        applicationId = "zaujaani.vibra"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // BuildConfig fields untuk logging dan fitur debug
        buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "false")
        buildConfigField("String", "BUILD_TYPE", "\"debug\"")
        buildConfigField("boolean", "LOG_BLUETOOTH_DATA", "false")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false

            // Override untuk debug build
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "true")
            buildConfigField("String", "BUILD_TYPE", "\"debug\"")
            buildConfigField("boolean", "LOG_BLUETOOTH_DATA", "true")
        }

        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Override untuk release build
            buildConfigField("boolean", "ENABLE_VERBOSE_LOGGING", "false")
            buildConfigField("String", "BUILD_TYPE", "\"release\"")
            buildConfigField("boolean", "LOG_BLUETOOTH_DATA", "false")

            // Signing config (sesuaikan dengan milikmu)
            // signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xopt-in=kotlin.RequiresOptIn",
            "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/gradle/incremental.annotation.processors",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Untuk menghindari dex limit
    dexOptions {
        javaMaxHeapSize = "4g"
        jumboMode = true
    }

    // Build variant filter
    variantFilter {
        if (buildType.name == "debug" && flavors.any { it.name == "release" }) {
            ignore = true
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Room Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-paging:2.6.1")

    // Lifecycle Components
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.7.0")

    // Navigation Component
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Hilt Dependency Injection
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-fragment:1.1.0")

    // Logging - Optimized
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Maps & Location
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Charts
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")

    // Splash Screen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Bluetooth & Performance Optimizations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Untuk binary data processing
    implementation("com.google.guava:guava:32.1.3-android")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.50")
    kaptAndroidTest("com.google.dagger:hilt-android-compiler:2.50")
}

kapt {
    correctErrorTypes = true
    useBuildCache = true

    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
        arg("dagger.hilt.shareTestComponents", "false")
        arg("dagger.hilt.disableModulesHaveInstallInCheck", "true")
    }
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.22")

        // Cache dependencies
        cacheChangingModulesFor(0, "seconds")
        cacheDynamicVersionsFor(10, "minutes")

        // Prefer stable versions
        preferProjectModules()
    }
}