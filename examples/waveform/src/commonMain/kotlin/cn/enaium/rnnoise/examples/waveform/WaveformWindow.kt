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

import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotAxisFlags
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.roundToInt

/** Shortest window the slider allows, in milliseconds. */
internal const val MIN_WINDOW_MILLIS = 100

/** Longest window the slider allows, in milliseconds. */
internal const val MAX_WINDOW_MILLIS = 10_000

/** Window the example starts with, in milliseconds. */
internal const val DEFAULT_WINDOW_MILLIS = 1_000

/**
 * The example UI: the raw capture and the denoised signal drawn as two ImPlot
 * lines over the same sliding window, with the VAD probability RNNoise returns
 * for the frame that was processed last.
 *
 * Both plots share one amplitude scale. A microphone running at speech level
 * is roughly 30 dB below full scale, so a fixed `-1..1` axis would draw a flat
 * line; the shared scale keeps the two signals comparable - which is the whole
 * point of showing them side by side - and the level readout keeps the
 * absolute values visible.
 *
 * The window length is a slider: the capture scopes always hold
 * [MAX_WINDOW_MILLIS] and the plots show the newest part of them.
 */
class WaveformWindow(
    private val pipeline: DenoisePipeline,
    initialWindowMillis: Int = DEFAULT_WINDOW_MILLIS,
) {

    private val sliderValue = IntArray(1) { initialWindowMillis.coerceIn(MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS) }

    private val noiseEnabled = BooleanArray(1)

    private val noiseTypeIndex = IntArray(1) { pipeline.noiseType.ordinal }

    private val noiseLevelDb = FloatArray(1) { DEFAULT_NOISE_DB }

    private var windowMillis = sliderValue[0]

    private var input = FloatArray(windowSamples(windowMillis))

    private var denoised = FloatArray(windowSamples(windowMillis))

    /** Reused min/max envelope of both plots, sized to the current plot width. */
    private var envelope = FloatArray(0)

    private var scale = MIN_SCALE

    /** One frame of the UI. Call between [ImGui.newFrame] and [ImGui.render]. */
    fun draw() {
        val inputPeak = pipeline.inputScope.snapshot(input)
        val outputPeak = pipeline.outputScope.snapshot(denoised)
        // Fast attack, slow release: the axis follows a louder passage at once
        // and shrinks back over roughly a second.
        scale = maxOf(inputPeak * HEADROOM, outputPeak * HEADROOM, scale * RELEASE, MIN_SCALE)

        val displaySize = ImGui.getIO().displaySize
        // The window owns the whole viewport: pinned to the top-left corner,
        // resized to the display and stripped of decorations, so it cannot be
        // dragged, resized or collapsed and always fills the SDL window.
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(displaySize, ImGuiCond.ALWAYS)
        if (ImGui.begin("rnnoise-kmp waveform", null, WINDOW_FLAGS)) {
            val captureError = pipeline.captureError
            if (captureError == null) {
                ImGui.text("capture: ${pipeline.deviceName} via ${pipeline.systemName}")
            } else {
                // Android asks for the microphone permission while the app is
                // already running, so the device can appear a moment later.
                ImGui.text("no capture yet, retrying: $captureError")
            }
            ImGui.text("format: ${pipeline.format}, rnnoise frame: ${pipeline.frameSize} samples")
            ImGui.text("denoised ${pipeline.processedSamples / pipeline.frameSize} frames (10 ms each)")
            ImGui.text(
                "peak: input ${dbfs(inputPeak)} dBFS, output ${dbfs(outputPeak)} dBFS" +
                    " | amplitude axis: +-${fixed(scale.toDouble(), 4)}",
            )
            ImGui.text("speech probability: ${(pipeline.speechProbability * 100).roundToInt()}%")
            ImGui.sameLine()
            ImGui.progressBar(pipeline.speechProbability, ImVec2(220f, 0f), null)

            ImGui.text("window")
            ImGui.sameLine()
            ImGui.setNextItemWidth(420f)
            if (ImGui.sliderInt("##window", sliderValue, MIN_WINDOW_MILLIS, MAX_WINDOW_MILLIS, "%d ms")) {
                resizeWindow(sliderValue[0])
            }

            // Synthetic noise, so the denoiser has something to remove even in
            // a quiet room (or on a device whose microphone only delivers
            // silence).
            ImGui.text("noise ")
            ImGui.sameLine()
            ImGui.checkbox("##noise", noiseEnabled)
            ImGui.sameLine()
            ImGui.setNextItemWidth(140f)
            if (ImGui.combo("##noiseType", noiseTypeIndex, NOISE_TYPE_LABELS)) {
                pipeline.noiseType = NoiseType.entries[noiseTypeIndex[0]]
            }
            ImGui.sameLine()
            ImGui.setNextItemWidth(360f)
            ImGui.sliderFloat("##noiseLevel", noiseLevelDb, NOISE_OFF_DB, 0f, "%.0f dBFS")
            pipeline.noiseLevelDb = if (noiseEnabled[0]) noiseLevelDb[0] else NOISE_OFF_DB
            ImGui.separator()

            // The window is fullscreen, so the two plots split whatever height
            // is left instead of leaving the lower half empty.
            val available = ImGui.getContentRegionAvail()
            val plotHeight = maxOf((available.y - PLOT_GAP) / 2f, MIN_PLOT_HEIGHT)
            plot("input (raw)", input, INPUT_COLOR, plotHeight)
            plot("output (denoised)", denoised, OUTPUT_COLOR, plotHeight)
        }
        ImGui.end()
    }

    private fun resizeWindow(millis: Int) {
        val samples = windowSamples(millis)
        if (input.size == samples) return
        windowMillis = millis
        input = FloatArray(samples)
        denoised = FloatArray(samples)
    }

    private fun plot(title: String, values: FloatArray, color: ImVec4, height: Float) {
        if (!ImPlot.beginPlot(title, ImVec2(-1f, height))) return
        ImPlot.setupAxes("milliseconds", "amplitude", ImPlotAxisFlags.NONE, ImPlotAxisFlags.NONE)
        // The window scrolls, so the x axis is pinned to the full sweep.
        ImPlot.setupAxesLimits(
            0.0,
            windowMillis.toDouble(),
            -scale.toDouble(),
            scale.toDouble(),
            ImPlotCond.ALWAYS,
        )

        // A ten second window holds ~480k samples while the plot is only a few
        // thousand pixels wide, and every point costs a call into the C++ line
        // renderer - plotting the raw array is a hundred times the work for a
        // picture that cannot show the difference. One min/max pair per pixel
        // column keeps the envelope and the cost bounded by the width.
        val columns = maxOf(ImPlot.getPlotSize().x.toInt(), 1)
        val samplesPerColumn = (values.size + columns - 1) / columns
        val spec = ImPlotSpec(lineColor = color, lineWeight = 1f)
        if (samplesPerColumn <= 1) {
            ImPlot.plotLine("##$title", values, xScale = MILLIS_PER_SAMPLE, spec = spec)
        } else {
            if (envelope.size != columns * 2) envelope = FloatArray(columns * 2)
            envelopeOf(values, samplesPerColumn, envelope)
            ImPlot.plotLine(
                "##$title",
                envelope,
                xScale = samplesPerColumn / 2.0 * MILLIS_PER_SAMPLE,
                spec = spec,
            )
        }
        ImPlot.endPlot()
    }

    /**
     * Fills [destination] with one min/max pair per column, in sample order, so
     * the drawn line follows the signal instead of jumping between extremes.
     * Two points are always written per column, which is what makes the x scale
     * (`samplesPerColumn / 2` per point) line up with the time axis.
     */
    private fun envelopeOf(values: FloatArray, samplesPerColumn: Int, destination: FloatArray) {
        var written = 0
        var start = 0
        while (start < values.size) {
            val end = minOf(start + samplesPerColumn, values.size)
            var lowest = start
            var highest = start
            for (i in start until end) {
                if (values[i] < values[lowest]) lowest = i
                if (values[i] > values[highest]) highest = i
            }
            val first = minOf(lowest, highest)
            val second = maxOf(lowest, highest)
            destination[written++] = values[first]
            destination[written++] = values[second]
            start = end
        }
    }

    private fun dbfs(value: Float): String =
        if (value <= 0f) "-inf" else fixed(20.0 * log10(value.toDouble()), 1)

    private companion object {
        /** Fullscreen host window: no title bar, nothing to drag or resize. */
        const val WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
            ImGuiWindowFlags.NO_RESIZE or
            ImGuiWindowFlags.NO_MOVE or
            ImGuiWindowFlags.NO_SCROLLBAR or
            ImGuiWindowFlags.NO_COLLAPSE or
            ImGuiWindowFlags.NO_SAVED_SETTINGS or
            ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS or
            ImGuiWindowFlags.NO_NAV_FOCUS

        /** Space kept between the two plots. */
        const val PLOT_GAP = 8f

        /** Noise level the slider starts at once it is switched on. */
        const val DEFAULT_NOISE_DB = -40f

        val NOISE_TYPE_LABELS = NoiseType.entries.map { it.label }.toTypedArray()

        /** Floor for very small windows. */
        const val MIN_PLOT_HEIGHT = 80f

        /** Keeps the peaks below the top of the axis. */
        const val HEADROOM = 1.15f

        /** Per-frame release factor of the amplitude scale. */
        const val RELEASE = 0.97f

        const val MIN_SCALE = 1e-4f

        val INPUT_COLOR = ImVec4(0.95f, 0.45f, 0.35f, 1f)

        val OUTPUT_COLOR = ImVec4(0.35f, 0.85f, 0.5f, 1f)
    }
}

/** Samples a window of [millis] holds at RNNoise's fixed 48 kHz. */
internal fun windowSamples(millis: Int): Int = millis * RNNOISE_SAMPLE_RATE / 1000

/** One sample expressed in milliseconds, for the time axis. */
private const val MILLIS_PER_SAMPLE = 1000.0 / RNNOISE_SAMPLE_RATE

/**
 * Formats [value] with [decimals] digits after the point. `String.format` is
 * JVM only, and the example is shared with the native targets.
 */
internal fun fixed(value: Double, decimals: Int): String {
    var factor = 1L
    repeat(decimals) { factor *= 10 }
    val scaled = round(value * factor).toLong()
    val magnitude = if (scaled < 0) -scaled else scaled
    val whole = magnitude / factor
    val fraction = (magnitude % factor).toString().padStart(decimals, '0')
    return "${if (scaled < 0) "-" else ""}$whole.$fraction"
}
