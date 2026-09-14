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
 * A sliding window of mono samples, written by the capture drain and read by
 * the render loop.
 *
 * Both run on the same thread - the render loop pulls from the device - so the
 * window needs no synchronization.
 */
class WaveformScope(val capacity: Int) {

    private val samples = FloatArray(capacity)

    private var writeIndex = 0

    private var size = 0

    /** Appends [count] samples, overwriting the oldest ones once full. */
    fun append(values: FloatArray, count: Int = values.size) {
        for (i in 0 until count) {
            samples[writeIndex] = values[i]
            if (++writeIndex == capacity) writeIndex = 0
        }
        size = minOf(size + count, capacity)
    }

    /**
     * Copies the newest `destination.size` samples into [destination], oldest
     * sample first, and returns their peak magnitude. Fewer samples than that
     * are zeroed at the front, so the plot always draws a full sweep.
     *
     * The peak comes with the copy because both plots share one amplitude axis
     * and scanning the window twice would be pure overhead.
     */
    fun snapshot(destination: FloatArray): Float {
        val count = minOf(size, destination.size)
        val pad = destination.size - count
        destination.fill(0f, 0, pad)

        var index = writeIndex - count
        if (index < 0) index += capacity
        var peak = 0f
        for (i in 0 until count) {
            val value = samples[index]
            destination[pad + i] = value
            val magnitude = if (value < 0f) -value else value
            if (magnitude > peak) peak = magnitude
            if (++index == capacity) index = 0
        }
        return peak
    }
}
