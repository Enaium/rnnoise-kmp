import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kotlin/Native's own Android toolchain sysroot (API 26) ships the NDK stub
// libraries (libEGL, libGLESv2, libOpenSLES, libaaudio, ...) that SDL3's
// Android drivers reference at link time. Point -L at the per-ABI directory so
// the libmain.so link resolves them without needing an extra NDK install.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

// Shared by every Android ABI:
//  - compiler-rt builtins embedded in the sdl-kmp/imgui-kmp klibs overlap with
//    K/N's bundled libgcc on some ABIs (e.g. __sync_* on armv7), so the first
//    definition wins;
//  - Android devices come with either 4 KB or 16 KB memory pages, so every
//    PT_LOAD segment of libmain.so is aligned for both (the same flags the JNI
//    shared library is built with).
val androidMainLinkerOpts = listOf(
    "-Wl,--allow-multiple-definition",
    "-Wl,-z,max-page-size=16384",
    "-Wl,-z,common-page-size=16384",
)

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.rnnoise.examples.waveform.Main_jvmKt"
        }
    }

    // Native targets that every dependency publishes klibs for
    // (rnnoise-kmp, imgui-kmp, sdl-kmp and audio-io-kmp all ship macOS,
    // Linux x86_64 and Windows klibs, so the same UI source builds into a
    // standalone executable on each of them).
    macosArm64 {
        binaries.executable()
    }

    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    // Android native: SDLActivity (from the SDL3 Android archive) loads
    // libmain.so and calls its exported SDL_main, so the example is packaged as
    // a shared library here and turned into an APK by
    // :examples:waveform:android.
    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    // The repository disables the automatic default hierarchy template
    // (gradle.properties), so materialize it here: the JVM, native and Android
    // native targets need their shared intermediate source sets (nativeMain,
    // appleMain, macosMain, ...) for the common UI code and the entry points.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":rnnoise-kmp"))

                // Dear ImGui + ImPlot bindings and the SDL3 window/renderer
                // they are driven from; audio-io-kmp captures the PCM.
                implementation(libs.imgui.kmp)
                implementation(libs.sdl.kmp)
                implementation(libs.audio.io.kmp)
            }
        }
    }
}

// SDL3 has to own the first thread on macOS, otherwise video driver init fails
// with "No available video device". Mirrors the imgui-kmp examples.
// --enable-native-access silences the JNI warnings on JDK 24+.
// Native executables and the Android shared library need none of this: their
// main / SDL_main already runs on the thread SDL owns.
tasks.withType<JavaExec>().configureEach {
    if (OperatingSystem.current().isMacOsX) {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
