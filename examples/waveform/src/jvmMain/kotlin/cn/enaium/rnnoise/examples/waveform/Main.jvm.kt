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

import kotlin.system.exitProcess

/** Parses `--frames N` (exit after N frames, for headless runs). */
private fun parseFrames(args: Array<String>): Int {
    var frames = Int.MAX_VALUE
    var i = 0
    while (i < args.size) {
        if (args[i] == "--frames" && i + 1 < args.size) {
            frames = args[i + 1].toIntOrNull() ?: Int.MAX_VALUE
            i++
        }
        i++
    }
    return frames
}

/**
 * On macOS SDL has to own the first thread, so a JVM launched without
 * `-XstartOnFirstThread` (every IDE run configuration by default) cannot open
 * a window.
 */
internal actual val videoInitHint: String =
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        "\nThe JVM must start on the first thread for SDL: add -XstartOnFirstThread to the VM options." +
            "\n:examples:waveform:jvmRun sets it already; IDE run configurations have to add it themselves."
    } else {
        ""
    }

/** Desktop windows are left windowed; the ImGui window fills them instead. */
internal actual val windowStartsFullscreen: Boolean = false

/** JVM entry point: `./gradlew :examples:waveform:jvmRun`. */
fun main(args: Array<String>) {
    if (!runWaveformExample(parseFrames(args))) {
        exitProcess(1)
    }
}
