# rnnoise-kmp

[![Maven Central](https://img.shields.io/maven-central/v/cn.enaium.rnnoise/rnnoise-kmp?label=Maven%20Central)](https://central.sonatype.com/artifact/cn.enaium.rnnoise/rnnoise-kmp)
[![License](https://img.shields.io/github/license/Enaium/rnnoise-kmp)](https://github.com/Enaium/rnnoise-kmp/blob/main/LICENSE)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/Enaium/rnnoise-kmp/test.yml?label=test)](https://github.com/Enaium/rnnoise-kmp/actions/workflows/test.yml)
[![GitHub Repo stars](https://img.shields.io/github/stars/Enaium/rnnoise-kmp?style=social)](https://github.com/Enaium/rnnoise-kmp)

Kotlin Multiplatform bindings for [RNNoise](https://github.com/xiph/rnnoise) — a real-time noise suppression library from Xiph.Org. RNNoise uses a recurrent neural network to suppress stationary background noise while preserving speech, and doubles as a speech activity (VAD) detector.

## Supported Platforms

| Platform       | Targets                                                     | Mechanism                                  |
| -------------- | ----------------------------------------------------------- | ------------------------------------------ |
| **Android**    | arm64-v8a, armeabi-v7a, x86, x86_64                          | JNI (shared library via CMake)             |
| **JVM**        | Linux x86_64/aarch64, macOS arm64/x86_64, Windows x86_64     | JNI (per-OS/arch JAR resource, auto-extracted by `NativeLoader`) |
| **iOS**        | arm64, x64, simulatorArm64                                   | Kotlin/Native cinterop (static library)    |
| **macOS**      | arm64, x86_64                                                | Kotlin/Native cinterop (static library)    |
| **Linux**      | x86_64                                                       | Kotlin/Native cinterop (static library)    |
| **Windows**    | mingwX64                                                     | Kotlin/Native cinterop (static library)    |
| **tvOS**       | arm64, simulatorArm64                                        | Kotlin/Native cinterop (static library)    |
| **watchOS**    | arm64, simulatorArm64, deviceArm64                           | Kotlin/Native cinterop (static library)    |

## Gradle Dependency

**Kotlin Multiplatform / Android:**

```kotlin
implementation("cn.enaium.rnnoise:rnnoise-kmp:1.0.0")
```

**JVM:** the right native binary is resolved automatically — the `rnnoise-kmp-jvm` artifact pulls in the matching `:jni-jvm-*` sibling on the classpath:

- `rnnoise-kmp-jni-jvm-linux-x86_64`
- `rnnoise-kmp-jni-jvm-linux-aarch64`
- `rnnoise-kmp-jni-jvm-darwin-x86_64`
- `rnnoise-kmp-jni-jvm-darwin-aarch64`
- `rnnoise-kmp-jni-jvm-windows-x86_64`

`NativeLoader` detects `os.name`/`os.arch` at runtime, extracts the matching binary from the classpath to a temp directory, and `System.load`s it. No `java.library.path` setup is required for downstream JVM consumers. On Android the `.so` is loaded from the AAR's `jniLibs` via `System.loadLibrary`.

## Quick Start

RNNoise processes 10 ms frames — 480 samples at a **fixed 48 kHz** sample rate:

```kotlin
import cn.enaium.rnnoise.createRnnoise

// 1. Create the denoiser with the built-in default model
createRnnoise().use { rnnoise ->
    // 2. Process 10 ms frames (480 samples @ 48 kHz)
    val input = FloatArray(rnnoise.frameSize)   // noisy PCM, normalized to -1.0..1.0
    val output = FloatArray(rnnoise.frameSize)
    val speechProb = rnnoise.processFrame(input, output)  // VAD probability in [0, 1]
    // `output` now contains the denoised frame
}
```

### Custom Model

```kotlin
import cn.enaium.rnnoise.createRnnoise
import cn.enaium.rnnoise.createRnnoiseModelFromBuffer
import cn.enaium.rnnoise.createRnnoiseModelFromFilename

// From a file:
val model = createRnnoiseModelFromFilename("model.rnn")
createRnnoise(model).use { rnnoise -> /* ... */ }
model.close()  // after all denoisers using it are closed

// From a memory buffer (the buffer is copied internally):
val bytes: ByteArray = /* model bytes */
val model2 = createRnnoiseModelFromBuffer(bytes)
createRnnoise(model2).use { rnnoise -> /* ... */ }
model2.close()
```

## API Reference

```kotlin
fun createRnnoise(model: RnnoiseModel? = null): Rnnoise
fun createRnnoiseModelFromFilename(filename: String): RnnoiseModel
fun createRnnoiseModelFromBuffer(buffer: ByteArray): RnnoiseModel
```

### Rnnoise

| Member                                        | Description                                            |
| --------------------------------------------- | ------------------------------------------------------ |
| `frameSize`                                   | Samples per frame (480 = 10 ms @ 48 kHz)               |
| `processFrame(input, output)`                 | Denoises one frame; returns the speech probability     |
| `processFrame(input)`                         | Denoises one frame and returns the denoised frame      |

All implementations are `AutoCloseable`; call `close()` to release the native state.

## Example

The [`example/`](example/) module is an Android app with a Jetpack Compose UI:

- **Noise suppression switch** — toggle RNNoise on/off to hear the difference
- **Speech probability** — live VAD output from `processFrame`
- **Start/Stop button** — real-time `AudioRecord → RNNoise → AudioTrack` loopback at 48 kHz

## Building from Source

### Prerequisites

- JDK 17+
- CMake 3.16+
- Android SDK + NDK (for Android targets)
- Xcode command-line tools (for iOS/macOS/tvOS/watchOS targets)

### Clone with submodules

```bash
git clone --recursive https://github.com/Enaium/rnnoise-kmp.git
cd rnnoise-kmp
```

### Publish to Maven Local

The default denoising model weights are downloaded automatically from `media.xiph.org` during the first CMake configure and cached under `jni/c_api/`:

```bash
./gradlew :rnnoise-kmp:publishToMavenLocal
```

### Run tests

```bash
./gradlew :rnnoise-kmp:jvmTest        # JVM (JNI)
./gradlew :rnnoise-kmp:macosArm64Test # macOS native
./gradlew :rnnoise-kmp:linuxX64Test   # Linux native
```

## Project Structure

```
rnnoise-kmp/
├── rnnoise/                  # Git submodule (C library)
├── jni/
│   ├── CMakeLists.txt        # JNI shared library build
│   ├── jni_bridge.cpp        # JNI bridge (C++ → JVM/Android)
│   ├── c_api/                # Downloaded model weights (build-time, gitignored)
│   └── jvm/                  # Per-OS/arch JNI publication subprojects
│       ├── darwin-aarch64, darwin-x86_64
│       ├── linux-x86_64, linux-aarch64
│       └── windows-x86_64
├── rnnoise-kmp/              # Kotlin Multiplatform module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/       # expect declarations + common interfaces
│       ├── commonTest/
│       ├── jvmMain/          # JVM actual (JNI) + NativeLoader
│       ├── androidMain/      # Android actual (JNI)
│       ├── nativeMain/       # Native actual (cinterop)
│       └── nativeInterop/cinterop/
├── example/                  # Android Compose demo (loopback + noise suppression)
├── scripts/                  # Native build helpers
└── .github/workflows/        # publish + test
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file.
