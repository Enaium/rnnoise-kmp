import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kotlin/Native's own Android toolchain sysroot (API 26) ships the NDK stub
// libraries (libEGL, libGLESv2, libOpenSLES, libaaudio, ...) that SDL3's
// Android drivers reference at link time. Point -L at the per-ABI directory so
// the libmain.so link resolves them.
//
// The toolchain is downloaded while the build runs, not before it is
// configured, so on a cold cache (CI) it is not there yet when this script is
// evaluated - a locally installed NDK has the same stubs at a path that does
// exist then, and both are passed.
fun androidStubLibDirs(abi: String): List<String> {
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return emptyList()
    }
    val dirs = mutableListOf<String>()

    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: "${System.getProperty("user.home")}/.konan"
    File(konanData, "dependencies").listFiles()
        // Newest first: an older toolchain left in the cache has an older sysroot.
        ?.filter { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?.maxByOrNull { it.name }
        ?.resolve("sysroot/usr/lib/$triple/$ANDROID_API_LEVEL")
        ?.takeIf { it.isDirectory }
        ?.let { dirs += it.absolutePath }

    val ndk = resolveAndroidNdkDir()
    val host = when {
        OperatingSystem.current().isMacOsX -> "darwin-x86_64"
        OperatingSystem.current().isLinux -> "linux-x86_64"
        else -> null
    }
    if (ndk != null && host != null) {
        ndk.resolve("toolchains/llvm/prebuilt/$host/sysroot/usr/lib/$triple/$ANDROID_API_LEVEL")
            .takeIf { it.isDirectory }
            ?.let { dirs += it.absolutePath }
    }
    return dirs
}

/** Android API level the stub libraries are taken for (AAudio needs 26). */
val ANDROID_API_LEVEL = 26

/** The locally installed NDK, pinned version first, or `null`. */
fun resolveAndroidNdkDir(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "NDK_HOME").forEach { key ->
        System.getenv(key)?.takeIf { it.isNotBlank() }?.let { path ->
            val dir = File(path)
            if (dir.isDirectory) return dir
        }
    }
    val sdk = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
        .mapNotNull { System.getenv(it)?.takeIf { path -> path.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?: rootProject.file("local.properties").takeIf { it.isFile }?.let { props ->
            Properties().apply { props.inputStream().use { load(it) } }
                .getProperty("sdk.dir")
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
        }
        ?: return null
    val ndkParent = sdk.resolve("ndk")
    if (!ndkParent.isDirectory) return null
    return ndkParent.resolve(PINNED_NDK_VERSION).takeIf { it.isDirectory }
        ?: ndkParent.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

/** Matches android.ndkVersion in gradle.properties. */
val PINNED_NDK_VERSION = "27.0.12077973"

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
            androidStubLibDirs("arm64-v8a").forEach { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeArm32 {
        binaries.sharedLib("main") {
            androidStubLibDirs("armeabi-v7a").forEach { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX64 {
        binaries.sharedLib("main") {
            androidStubLibDirs("x86_64").forEach { linkerOpts("-L$it") }
            linkerOpts(*androidMainLinkerOpts.toTypedArray())
        }
    }

    androidNativeX86 {
        binaries.sharedLib("main") {
            androidStubLibDirs("x86").forEach { linkerOpts("-L$it") }
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
