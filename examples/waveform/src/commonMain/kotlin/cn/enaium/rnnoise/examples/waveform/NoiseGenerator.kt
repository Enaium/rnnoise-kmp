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

import kotlin.math.pow
import kotlin.random.Random

/** Spectra of the synthetic noise the example can mix into the capture. */
enum class NoiseType(val label: String) {
    /** Flat spectrum, equal energy per hertz. */
    WHITE("White"),

    /** -3 dB per octave, the spectrum of most everyday background noise. */
    PINK("Pink"),

    /** -6 dB per octave, the rumble of traffic and air conditioning. */
    BROWN("Brown"),
}

/**
 * Generates the noise that is mixed into the captured signal, so the denoiser
 * has something to remove even in a quiet room (and on a device whose
 * microphone only delivers silence).
 *
 * The generator is seeded, so a given level and type always produce the same
 * signal - which makes screenshots and measurements comparable.
 */
class NoiseGenerator(seed: Int = 0x5EED) {

    private val random = Random(seed)

    // Paul Kellet's pink filter and the brown integrator keep their state
    // between calls, so the noise stays continuous across frame boundaries.
    private var pink0 = 0f
    private var pink1 = 0f
    private var pink2 = 0f
    private var brown = 0f

    /**
     * Adds [type] noise at [amplitude] (linear, `0..1`, i.e. [dbfsToAmplitude])
     * to the first [count] samples of [destination].
     */
    fun add(destination: FloatArray, count: Int, type: NoiseType, amplitude: Float) {
        if (amplitude <= 0f) return
        for (i in 0 until count) {
            destination[i] += amplitude * sample(type)
        }
    }

    private fun sample(type: NoiseType): Float {
        val white = random.nextFloat() * 2f - 1f
        return when (type) {
            NoiseType.WHITE -> white

            NoiseType.PINK -> {
                pink0 = 0.99765f * pink0 + white * 0.0990460f
                pink1 = 0.96300f * pink1 + white * 0.2965164f
                pink2 = 0.57000f * pink2 + white * 1.0526913f
                (pink0 + pink1 + pink2 + white * 0.1848f) * PINK_GAIN
            }

            NoiseType.BROWN -> {
                brown = (brown + 0.02f * white) / 1.02f
                // The integrator loses level, so the result is scaled back up to
                // roughly the peak of the white source.
                brown * BROWN_GAIN
            }
        }
    }

    private companion object {
        // Kellet's filter sums to about twice the white amplitude, and the
        // brown integrator loses most of it; these factors give every type the
        // same RMS at the same level, so the level slider means one thing.
        const val PINK_GAIN = 0.36f

        const val BROWN_GAIN = 10.6f
    }
}

/** The linear amplitude of [db] dBFS. */
fun dbfsToAmplitude(db: Float): Float = 10f.pow(db / 20f)

/** Level at which nothing is mixed into the capture. */
const val NOISE_OFF_DB = -80f
