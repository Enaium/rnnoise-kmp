import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.android.application)
}

// The KMP example module builds libmain.so (with an exported SDL_main) for
// every androidNative ABI; copy those into jniLibs and depend on the link tasks
// so the APK is assembled after them. libc++_shared.so is packaged next to it
// because libmain.so links the shared C++ runtime that the stock Android
// emulator system image does not ship.
val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

val androidCxxTriples = mapOf(
    "androidNativeArm64" to "aarch64-linux-android",
    "androidNativeArm32" to "arm-linux-androideabi",
    "androidNativeX64" to "x86_64-linux-android",
    "androidNativeX86" to "i686-linux-android",
)

abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cxxSharedTriples: MapProperty<String, String>

    @TaskAction
    fun run() {
        val bin = project.layout.projectDirectory.dir("../build/bin").asFile
        outputDir.get().asFile.deleteRecursively()

        // Kotlin/Native's Android toolchain ships libc++_shared.so in its
        // sysroot; a locally installed NDK has one too.
        fun cxxShared(target: String): File? {
            val triple = cxxSharedTriples.get()[target] ?: return null
            val konanData = System.getenv("KONAN_DATA_DIR")
                ?: System.getProperty("user.home")?.let { File(it, ".konan") }?.absolutePath
            val fromKonan = konanData?.let { data ->
                File(data, "dependencies").listFiles()
                    ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
                    ?.resolve("sysroot/usr/lib/$triple/libc++_shared.so")
            }
            if (fromKonan != null && fromKonan.isFile) return fromKonan

            val sdk = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: return null
            val host = when {
                OperatingSystem.current().isMacOsX -> "darwin-" + System.getProperty("os.arch")
                OperatingSystem.current().isLinux -> "linux-" + System.getProperty("os.arch")
                else -> return null
            }
            return File(sdk, "ndk").listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)+")) }
                ?.maxByOrNull { it.name }
                ?.resolve("toolchains/llvm/prebuilt/$host/sysroot/usr/lib/$triple/libc++_shared.so")
                ?.takeIf { it.isFile }
        }

        abis.get().forEach { (target, abi) ->
            val source = File(bin, "$target/mainDebugShared/libmain.so")
            if (!source.isFile) {
                throw GradleException(
                    "Expected $source - did linkMainDebugShared${target.replaceFirstChar { it.uppercase() }} fail?",
                )
            }
            val abiDir = File(outputDir.get().asFile, abi).apply { mkdirs() }
            source.copyTo(File(abiDir, "libmain.so"), overwrite = true)

            val shared = cxxShared(target)
            if (shared == null) {
                logger.warn("No libc++_shared.so for $abi; libmain.so may fail to load at runtime.")
            } else {
                shared.copyTo(File(abiDir, "libc++_shared.so"), overwrite = true)
            }
        }
    }
}

val prepareJniLibs = tasks.register<PrepareJniLibsTask>("prepareJniLibs") {
    group = "build"
    description = "Collects the linked libmain.so of every Android ABI into jniLibs."
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    abis.set(androidAbis)
    cxxSharedTriples.set(androidCxxTriples)
}

androidAbis.keys.forEach { target ->
    val linkTask = project(":examples:waveform").tasks.named(
        "linkMainDebugShared${target.replaceFirstChar { it.uppercase() }}",
    )
    prepareJniLibs.configure {
        dependsOn(linkTask)
        // Re-package whenever a linked libmain.so changes; without this the task
        // stays UP-TO-DATE and a rebuilt library never reaches the APK.
        inputs.files(linkTask.flatMap { (it as org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink).outputFile })
    }
}

android {
    namespace = "cn.enaium.rnnoise.examples.waveform"
    compileSdk = 37

    defaultConfig {
        applicationId = "cn.enaium.rnnoise.examples.waveform"
        // AAudio, the capture backend audio-io-kmp uses on Android native,
        // exists from API 26 on.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

// Register the collected libmain.so directory with AGP's variant API.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepareJniLibs) { it.outputDir }
    }
}

dependencies {
    // sdl-kmp's Android variant carries SDL's Java layer (org.libsdl.app:
    // SDLActivity, SDLSurface, ...) and its own JNI library; libmain.so links
    // SDL3 statically and exports the JNI entry points SDLActivity calls.
    implementation(libs.sdl.kmp)
}
