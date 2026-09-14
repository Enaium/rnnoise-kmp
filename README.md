# rnnoise-kmp

![](https://img.cdn1.vip/i/6aa8170f00684_1789400847.webp)

[![Maven Central](https://img.shields.io/maven-central/v/cn.enaium.rnnoise/rnnoise-kmp?label=Maven%20Central)](https://central.sonatype.com/artifact/cn.enaium.rnnoise/rnnoise-kmp)
[![License](https://img.shields.io/github/license/Enaium/rnnoise-kmp)](https://github.com/Enaium/rnnoise-kmp/blob/main/LICENSE)
[![GitHub Actions](https://img.shields.io/github/actions/workflow/status/Enaium/rnnoise-kmp/test.yml?label=test)](https://github.com/Enaium/rnnoise-kmp/actions/workflows/test.yml)
[![GitHub Repo stars](https://img.shields.io/github/stars/Enaium/rnnoise-kmp?style=social)](https://github.com/Enaium/rnnoise-kmp)

Kotlin Multiplatform bindings for [RNNoise](https://github.com/xiph/rnnoise) — a real-time noise suppression library from Xiph.Org. RNNoise uses a recurrent neural network to suppress stationary background noise while preserving speech, and doubles as a speech activity (VAD) detector.

## Supported Platforms

| Platform       | Targets                                                     | Mechanism                                  |
| -------------- | ----------------------------------------------------------- | ------------------------------------------ |
| **Android**    | arm64-v8a, armeabi-v7a, x86, x86_64                          | JNI (shared library via CMake)             |
| **Android Native** | arm64-v8a, armeabi-v7a, x86_64, x86                      | Kotlin/Native cinterop (static library, NDK cross-compiled) |
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
implementation("cn.enaium.rnnoise:rnnoise-kmp:1.0.2")
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

The `-1.0..1.0` range is what this API works in. RNNoise itself is trained and gated on the int16 sample range — its own demo feeds `short` samples straight into the float API — so `processFrame` scales the frame up on the way in and the result back down. Without that, frames below RNNoise's silence gate would pass through untouched no matter how much noise they carry.

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

## Examples

### `examples/basic` — Android loopback

An Android app with a Jetpack Compose UI:

- **Noise suppression switch** — toggle RNNoise on/off to hear the difference
- **Speech probability** — live VAD output from `processFrame`
- **Start/Stop button** — real-time `AudioRecord → RNNoise → AudioTrack` loopback at 48 kHz

```bash
./gradlew :examples:basic:assembleDebug
```

### `examples/waveform` — ImGui/ImPlot waveform viewer

A Kotlin Multiplatform app that draws the raw microphone signal and the denoised signal side by side with [Dear ImGui](https://github.com/ocornut/imgui) and [ImPlot](https://github.com/epezent/implot), on a sliding window whose length a slider sets between 100 ms and 10 s (one second to start with):

```bash
# JVM
./gradlew :examples:waveform:jvmRun
./gradlew :examples:waveform:jvmRun --args="--frames 300"   # exit after 300 frames

# Native executable (macosArm64 shown; macosX64, linuxX64 and mingwX64 build the same way)
./gradlew :examples:waveform:linkReleaseExecutableMacosArm64
RNNOISE_KMP_FRAMES=300 ./examples/waveform/build/bin/macosArm64/releaseExecutable/waveform.kexe

# Android (needs a device or emulator)
./gradlew :examples:waveform:android:assembleDebug
adb install -r examples/waveform/android/build/outputs/apk/debug/android-debug.apk
```

On Android the example is a Kotlin/Native shared library: the app module packages `libmain.so` (which links SDL3, ImGui/ImPlot, RNNoise and the AAudio backend statically) for every ABI, and `SDLActivity` loads it and calls its exported `SDL_main`. The activity asks for the microphone permission on first launch, runs in landscape and lets SDL hide the system bars, so the plots own the whole screen.

The ImGui window fills the SDL window - no title bar, nothing to drag or resize - and the two plots split its height.

The controls under the readouts are the window length (100 ms - 10 s) and the synthetic noise the example can mix into the capture: a colour (white / pink / brown, all three normalized to the same RMS), a level in dBFS, and a checkbox that switches it on. Turning it on is how the denoiser's effect becomes visible in a quiet room.

RNNoise itself is trained and gated on the int16 sample range, so `processFrame` scales the `-1..1` frame up on the way in and back on the way out; that is what makes the suppression apply at microphone levels instead of only near full scale. Measured through the library: white noise around -20 dBFS comes back ~50 dB quieter, while a speech-like harmonic stack at the same level is kept (about -3 dB).

> **macOS + JVM:** SDL has to own the first thread, so an IDE run configuration needs
> `-XstartOnFirstThread --enable-native-access=ALL-UNNAMED` in its VM options. `:examples:waveform:jvmRun`
> and the native executables already run on the first thread; without the flag SDL cannot open a window and
> the example fails with that message instead of rendering invisibly.

`sdl-kmp`, `imgui-kmp`, `audio-io-kmp` and `rnnoise-kmp` all publish the macOS, Linux x86_64 and Windows targets the example needs, so the same UI source also links into a standalone binary. Native *debug* executables drive the ImPlot renderer without optimizations (a few frames per second), so link the release executable for a smooth window.

Capture uses [audio-io-kmp](https://github.com/Enaium/audio-io-kmp) (`JavaSound` on the JVM, `Core Audio` / `ALSA` / `WASAPI` natively) and the UI is rendered through the SDL3 backends published by [imgui-kmp](https://github.com/Enaium/imgui-kmp). Both plots share one auto-scaled amplitude axis so the denoising is directly visible, and the header reports the peak level of each signal in dBFS.

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
├── examples/
│   ├── basic/                # Android Compose demo (loopback + noise suppression)
│   └── waveform/             # ImGui/ImPlot demo, JVM + native + Android (raw vs denoised waveform)
├── scripts/                  # Native build helpers
└── .github/workflows/        # publish + test
```

## License

[MIT](LICENSE) — see the [LICENSE](LICENSE) file.
