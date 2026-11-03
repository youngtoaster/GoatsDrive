plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.apexlink.projection"
    compileSdk = 34

    defaultConfig {
        // NOTE: For FYT / Topway / T3L head units where proprietary MCU sweeps kill non-whitelisted apps,
        // switch applicationId to "com.zjinnova.zlink" to inherit hardware steering keys & BT triggers.
        applicationId = "com.apexlink.projection"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-PROJECTION"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.addAll(
                    listOf(
                        "-std=c++17",
                        "-O3",
                        "-flto",
                        "-fvisibility=hidden",
                        "-fvisibility-inlines-hidden",
                        "-ffast-math",
                        "-Wall",
                        "-Wextra",
                        "-pthread",
                        "-DNDEBUG"
                    )
                )
                arguments.addAll(
                    listOf(
                        "-DANDROID_STL=c++_static",
                        "-DANDROID_PLATFORM=android-24"
                    )
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions"
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources {
            excludes.addAll(
                listOf(
                    "META-INF/*.version",
                    "META-INF/DEPENDENCIES",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*"
                )
            )
        }
    }
}

dependencies {
    // Pure Android SDK & Kotlin Stdlib only.
    // Strictly banned: Jetpack Compose, Material Design, RxJava, Retrofit, OkHttp.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
