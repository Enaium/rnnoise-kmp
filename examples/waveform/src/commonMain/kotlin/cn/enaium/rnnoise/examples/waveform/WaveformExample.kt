/*
 * Copyright (c) 2026 Enaium
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package cn.enaium.rnnoise.examples.waveform

/**
 * Platform specific advice appended when SDL cannot open a video device, or
 * empty when the platform has nothing to add.
 */
internal expect val videoInitHint: String

/**
 * Whether SDL should make the window fullscreen as soon as it is created.
 *
 * On Android the SDL window always covers the whole display, and fullscreen is
 * what makes SDL hide the status and navigation bars, which would otherwise
 * draw on top of the ImGui window (SDLActivity owns that decision, so hiding
 * them from the activity is not enough).
 */
internal expect val windowStartsFullscreen: Boolean

/**
 * Live RNNoise visualization, shared by the JVM and native entry points: the
 * microphone is captured at 48 kHz, every 10 ms frame is denoised by
 * [DenoisePipeline] and both signals are drawn with ImPlot in an SDL window.
 *
 * The capture scopes are sized for the longest window the UI can show, so the
 * slider can widen it without touching the pipeline.
 *
 * [frames] bounds the run, which is what the automated (headless) runs use.
 * Returns `false` when no window could be opened.
 */
fun runWaveformExample(frames: Int = Int.MAX_VALUE): Boolean {
    println("rnnoise-kmp waveform example (frames=$frames)")
    val displayed = DenoisePipeline(windowSamples(MAX_WINDOW_MILLIS)).use { pipeline ->
        val window = WaveformWindow(pipeline)
        ImGuiSdlApp.run("rnnoise-kmp waveform", frames) {
            // Pull the capture device on the render thread, then draw what it
            // produced.
            pipeline.drain()
            window.draw()
        }
    }
    println(if (displayed) "done" else "aborted")
    return displayed
}
