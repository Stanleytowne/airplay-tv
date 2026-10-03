import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeys = Properties().apply {
    System.getenv("AIRPLAY_SIGNING_PROPERTIES")?.let { path ->
        file(path).inputStream().use { load(it) }
    }
}
val targetAbis = providers.gradleProperty("airplayAbis")
    .orElse("armeabi-v7a,arm64-v8a,x86_64").get().split(",")

android {
    namespace = "com.flymop.airplaytv"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.flymop.airplaytv.home"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.1.8-home"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                )
            }
        }

        ndk {
            abiFilters.addAll(targetAbis)
        }
    }

    signingConfigs {
        if (releaseKeys.isNotEmpty()) {
            create("homeRelease") {
                storeFile = file(releaseKeys.getProperty("storeFile"))
                storePassword = releaseKeys.getProperty("storePassword")
                keyAlias = releaseKeys.getProperty("keyAlias")
                keyPassword = releaseKeys.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("homeRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*targetAbis.toTypedArray())
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        prefab = true
    }
}

tasks.register("applyUxplayPatches") {
    val patchDir = file("src/main/cpp/patches/UxPlay")
    val uxplayDir = file("src/main/cpp/third_party/UxPlay")
    inputs.dir(patchDir)
    outputs.dir(uxplayDir)
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", uxplayDir.absolutePath, *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = patchDir.listFiles { f -> f.extension == "patch" }?.sortedBy { it.name } ?: emptyList()
        if (patches.isNotEmpty()) {
            val touched = patches.flatMap {
                try {
                    git("apply", "--numstat", it.absolutePath).trim().lines()
                } catch (e: Exception) {
                    emptyList()
                }
            }
                .map { it.substringAfterLast("\t").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            if (touched.isNotEmpty()) {
                git("checkout", "--", *touched.toTypedArray())
            }
            patches.forEach { patch ->
                val checkProc = ProcessBuilder("git", "-C", uxplayDir.absolutePath, "apply", "--check", "--unidiff-zero", patch.absolutePath)
                    .redirectErrorStream(true).start()
                val checkOutput = checkProc.inputStream.bufferedReader().readText()
                check(checkProc.waitFor() == 0) { "Required patch ${patch.name} failed:\n$checkOutput" }
                git("apply", "--unidiff-zero", patch.absolutePath)
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
    if (name == "preReleaseBuild") doFirst {
        check(releaseKeys.isNotEmpty()) {
            "Set AIRPLAY_SIGNING_PROPERTIES to your private signing properties file before building a release."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.leanback:leanback:1.0.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")

    // Oboe low latency audio
    implementation("com.google.oboe:oboe:1.9.3")

    // Android media
    implementation("androidx.media:media:1.7.0")

    // Media3 / ExoPlayer for HLS mode
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
