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

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioException
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioInput
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.audioSystem
import cn.enaium.rnnoise.Rnnoise
import cn.enaium.rnnoise.createRnnoise

/** RNNoise processes 48 kHz mono only. */
internal const val RNNOISE_SAMPLE_RATE = 48_000

/** Frames buffered by the device, in RNNoise frames: 10 x 10 ms. */
private const val BUFFER_FRAMES = 10

/** UI frames between two attempts to open the capture device. */
private const val RETRY_FRAMES = 30

/**
 * The capture half of the example: reads the default input device at RNNoise's
 * fixed 48 kHz mono format, denoises every 10 ms frame and publishes the raw
 * and the denoised samples into [inputScope] / [outputScope].
 *
 * The device is drained from the render loop ([drain]) instead of a background
 * thread: pulling with a non-blocking read keeps the example free of platform
 * thread APIs, so the same code runs on the JVM, as a native executable and on
 * Android. The device buffers [BUFFER_FRAMES] RNNoise frames, which is far more
 * than one UI frame, so a slow frame cannot drop audio.
 */
class DenoisePipeline(
    /**
     * Samples covered by one waveform window; the example sizes it for the
     * longest window its slider allows and lets the UI show a shorter part.
     */
    windowSamples: Int,
) : AutoCloseable {

    /** Raw samples, as captured. */
    val inputScope = WaveformScope(windowSamples)

    /** Denoised samples, aligned with [inputScope]. */
    val outputScope = WaveformScope(windowSamples)

    /** Format requested from the device. */
    val format: AudioFormat = AudioFormat.SPEECH

    /** Samples per RNNoise frame (480 = 10 ms at 48 kHz). */
    val frameSize: Int

    /** Backend serving the device, e.g. `"Core Audio"`. */
    val systemName: String

    /** Human readable name of the capture endpoint, once one is open. */
    var deviceName: String = "system default"
        private set

    /** Why no capture stream is open, or `null` while one is. */
    var captureError: String? = null
        private set

    /** Latest VAD output of [Rnnoise.processFrame], in `0..1`. */
    var speechProbability: Float = 0f
        private set

    /** Colour of the synthetic noise mixed into the capture. */
    var noiseType: NoiseType = NoiseType.WHITE

    /** Level of the synthetic noise, or [NOISE_OFF_DB] to mix in nothing. */
    var noiseLevelDb: Float = NOISE_OFF_DB
        set(value) {
            field = value
            noiseAmplitude = if (value <= NOISE_OFF_DB) 0f else dbfsToAmplitude(value)
        }

    private var noiseAmplitude = 0f

    private val noise = NoiseGenerator()

    /** Samples denoised since the device was opened. */
    var processedSamples: Long = 0L
        private set

    private val system: AudioSystem = audioSystem()

    private val rnnoise: Rnnoise = createRnnoise()

    private var input: AudioInput? = null

    // Scratch for one device read. It must fit whatever the device hands back
    // in a single call - a non-blocking read fills up to the whole buffer -
    // otherwise the conversion would silently clip the rest.
    private val buffer: AudioBuffer

    private val samples: FloatArray

    private val frame: FloatArray

    private val denoised: FloatArray

    // Carry-over for the partial frame between two reads; always shorter than
    // one RNNoise frame.
    private val carry: FloatArray

    private var carryLength = 0

    private var retryCountdown = 0

    private var closed = false

    init {
        frameSize = rnnoise.frameSize
        systemName = system.name
        buffer = AudioBuffer(format, frameSize * BUFFER_FRAMES)
        samples = FloatArray(frameSize * BUFFER_FRAMES)
        frame = FloatArray(frameSize)
        denoised = FloatArray(frameSize)
        carry = FloatArray(frameSize)
    }

    /**
     * Opens the capture device if needed and denoises everything it buffered.
     * Called once per UI frame; returns how many samples were denoised.
     *
     * Opening is retried rather than attempted once up front: on Android the
     * microphone permission is granted while the app already runs, and on
     * desktop a device can appear later as well. The first failure is published
     * in [captureError] for the UI.
     */
    fun drain(): Int {
        if (closed) return 0
        val stream = input ?: openAfterRetry() ?: return 0

        var processed = 0
        while (true) {
            val frames = stream.readNonBlocking(buffer)
            if (frames <= 0) break
            processed += process(frames)
        }
        return processed
    }

    /** Opens the device at most once every [RETRY_FRAMES] calls. */
    private fun openAfterRetry(): AudioInput? {
        if (retryCountdown > 0) {
            retryCountdown--
            return null
        }
        retryCountdown = RETRY_FRAMES
        return openCapture()?.also { input = it }
    }

    private fun openCapture(): AudioInput? = try {
        // bufferFrames: the device hands over up to 100 ms at a time, which is
        // enough slack for one slow UI frame and small enough to stay live.
        val stream = system.openInput(format, bufferFrames = frameSize * BUFFER_FRAMES)
        check(stream.format.channelCount == 1 && stream.format.sampleRate == RNNOISE_SAMPLE_RATE) {
            "RNNoise needs 48 kHz mono, but the device opened as ${stream.format}"
        }
        stream.start()
        deviceName = stream.device?.name ?: system.defaultInputDevice()?.name ?: "system default"
        captureError = null
        println("capturing $deviceName [$systemName] $format")
        stream
    } catch (e: AudioException) {
        if (captureError == null) {
            val available = system.inputDevices().joinToString { it.name }
            captureError = "${e.message} - inputs: [$available]"
            println("capture unavailable: $captureError; retrying")
        }
        null
    }

    /** Denoises every complete frame in the first [frames] captured frames. */
    private fun process(frames: Int): Int {
        val count = buffer.toFloats(samples, frames)
        // Mixed in before denoising: the raw trace then shows the noise and the
        // denoised one shows what RNNoise did with it.
        noise.add(samples, count, noiseType, noiseAmplitude)
        var offset = 0
        var processed = 0

        // Complete the partial frame left over from the previous read first.
        if (carryLength > 0) {
            val needed = frameSize - carryLength
            if (count < needed) {
                samples.copyInto(carry, carryLength, 0, count)
                carryLength += count
                return 0
            }
            samples.copyInto(carry, carryLength, 0, needed)
            denoise(carry)
            processed += frameSize
            offset = needed
            carryLength = 0
        }

        while (offset + frameSize <= count) {
            samples.copyInto(frame, 0, offset, offset + frameSize)
            denoise(frame)
            processed += frameSize
            offset += frameSize
        }

        val remaining = count - offset
        if (remaining > 0) {
            samples.copyInto(carry, 0, offset, count)
            carryLength = remaining
        }
        return processed
    }

    private fun denoise(frame: FloatArray) {
        speechProbability = rnnoise.processFrame(frame, denoised)
        inputScope.append(frame, frameSize)
        outputScope.append(denoised, frameSize)
        processedSamples += frameSize
    }

    /** Releases the device (if one was opened), the denoiser and the backend. */
    override fun close() {
        if (closed) return
        closed = true
        input?.close()
        input = null
        rnnoise.close()
        system.close()
    }
}
