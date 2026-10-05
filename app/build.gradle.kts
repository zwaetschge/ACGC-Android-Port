import java.net.URI
import java.util.Base64
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

// Third-party native sources are downloaded at build time (not committed):
//  - SDL2          (zlib)            game window, input, audio, GLES context
//  - astc-encoder  (Apache-2.0)      HD texture pack conversion on device
//  - bcdec         (MIT/Unlicense)   BC1/BC3/BC7 decoder for the same
val thirdParty = file("src/main/cpp/third_party")
val sdlVersion = "2.30.12"
val astcencVersion = "5.7.0"
val bcdecCommit = "80859ed3b7afb1c527a2a99d70c61457bea72d0c"

fun download(url: String, dst: File) {
    dst.parentFile.mkdirs()
    URI(url).toURL().openStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
}

val fetchThirdParty by tasks.registering {
    outputs.dir(thirdParty)
    doLast {
        val tmp = layout.buildDirectory.dir("third_party_dl").get().asFile
        if (!File(thirdParty, "SDL2/CMakeLists.txt").exists()) {
            val tgz = File(tmp, "sdl2.tar.gz")
            download("https://github.com/libsdl-org/SDL/releases/download/release-$sdlVersion/SDL2-$sdlVersion.tar.gz", tgz)
            copy { from(tarTree(resources.gzip(tgz))); into(tmp) }
            File(tmp, "SDL2-$sdlVersion").renameTo(File(thirdParty, "SDL2"))
        }
        if (!File(thirdParty, "astc-encoder/Source/astcenc.h").exists()) {
            val tgz = File(tmp, "astcenc.tar.gz")
            download("https://github.com/ARM-software/astc-encoder/archive/refs/tags/$astcencVersion.tar.gz", tgz)
            copy { from(tarTree(resources.gzip(tgz))); into(tmp) }
            File(tmp, "astc-encoder-$astcencVersion").renameTo(File(thirdParty, "astc-encoder"))
        }
        val bcdec = File(thirdParty, "bcdec/bcdec.h")
        if (!bcdec.exists()) {
            download("https://raw.githubusercontent.com/iOrange/bcdec/$bcdecCommit/bcdec.h", bcdec)
        }
        tmp.deleteRecursively()
    }
}

tasks.matching { it.name == "preBuild" || it.name.startsWith("configureCMake") }
    .configureEach { dependsOn(fetchThirdParty) }

// Release signing: keystore.properties (not committed) with storePassword, keyPassword,
// keyAlias and either storeFile (path) or storeBase64. Without it, release builds are
// signed with the debug key so `assembleRelease` still produces an installable APK.
val signingProps = rootProject.file("keystore.properties")

android {
    namespace = "com.acpc.port"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.acpc.port"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0"
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

    signingConfigs {
        create("release") {
            if (signingProps.exists()) {
                val p = Properties().apply { signingProps.inputStream().use { load(it) } }
                val b64 = p.getProperty("storeBase64")
                storeFile = if (b64 != null) {
                    layout.buildDirectory.file("signing/release.p12").get().asFile.apply {
                        parentFile.mkdirs()
                        writeBytes(Base64.getMimeDecoder().decode(b64))
                    }
                } else {
                    rootProject.file(p.getProperty("storeFile"))
                }
                storeType = "pkcs12"
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (signingProps.exists()) "release" else "debug")
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

chaquopy {
    defaultConfig {
        version = "3.11"
        // pure-Python tools only: no pip packages, no build-time .pyc step
        pyc { src = false }
    }
}

dependencies {
    implementation("androidx.core:core:1.13.1")
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
}
