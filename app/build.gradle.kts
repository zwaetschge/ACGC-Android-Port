import java.io.FileOutputStream
import java.util.Base64

plugins {
    id("com.android.application")
}

// Game data: compact GAFE01 disc image rebuilt from the user's own dump
// (tools/build_iso.py). The REST API only transports text, so the binary
// travels base64-encoded in two parts and is assembled here. This task also
// removes the assets of the previous embedded layout (orig/).
val prepareGameAssets by tasks.registering {
    doLast {
        try {
            val stale = file("src/main/assets/orig")
            if (stale.exists()) {
                stale.deleteRecursively()
                logger.lifecycle("removed stale asset tree ${stale.path}")
            }
            val expected = 27_573_708L
            val dst = file("src/main/assets/rom/GAFE01.iso")
            if (!dst.exists() || dst.length() != expected) {
                dst.parentFile.mkdirs()
                // streaming decode — keeps the daemon heap small
                val dec = Base64.getMimeDecoder()
                FileOutputStream(dst).use { out ->
                    listOf("part1", "part2").forEach { part ->
                        file("game/b64/GAFE01.iso.b64.$part").inputStream().use { ins ->
                            dec.wrap(ins).copyTo(out)
                        }
                    }
                }
                check(dst.length() == expected) {
                    "GAFE01.iso decoded to ${dst.length()}, expected $expected"
                }
                logger.lifecycle("decoded game disc image (${dst.length()} bytes)")
            }
        } catch (t: Throwable) {
            file("prepareGameAssets.error.txt").writeText(t.stackTraceToString())
            throw t
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(prepareGameAssets) }

android {
    namespace = "com.acpc.port"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.acpc.port"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            // The decomp's emu64 display list interpreter packs host pointers
            // into 32-bit GBI words -> the port is 32-bit only (like upstream).
            abiFilters += listOf("armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
}
