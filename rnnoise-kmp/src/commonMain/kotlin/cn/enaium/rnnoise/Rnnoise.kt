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

package cn.enaium.rnnoise

// =========================================================================
// Top-level expect factory functions
// =========================================================================

/**
 * Creates an RNNoise denoiser.
 *
 * @param model optional custom [RnnoiseModel]; `null` uses the built-in
 *   default model. The model must outlive the denoiser.
 */
expect fun createRnnoise(model: RnnoiseModel? = null): Rnnoise

/**
 * Loads a custom denoising model from a file. The model must be freed with
 * [RnnoiseModel.close] after all denoisers using it are closed.
 */
expect fun createRnnoiseModelFromFilename(filename: String): RnnoiseModel

/**
 * Loads a custom denoising model from a memory buffer. The buffer is copied,
 * so the caller may reuse or discard the [ByteArray] freely. The model must
 * be freed with [RnnoiseModel.close] after all denoisers using it are
 * closed.
 */
expect fun createRnnoiseModelFromBuffer(buffer: ByteArray): RnnoiseModel

// =========================================================================
// Common interfaces
// =========================================================================

/** A custom denoising model loaded from a file or a memory buffer. */
interface RnnoiseModel : AutoCloseable

/**
 * RNNoise is trained and gated on the int16 sample range - its own demo feeds
 * `short` samples straight into the float API - while this API works in -1..1.
 * Every implementation scales a frame by this factor on the way in and back on
 * the way out, so the documented range denoises at any level; keep it in sync
 * with `kRnnoiseSampleScale` in `jni/jni_bridge.cpp`.
 */
internal const val RNNOISE_SAMPLE_SCALE = 32_768f

/**
 * An RNNoise denoiser. Processes audio in fixed-size frames of
 * [frameSize] samples (480 samples = 10 ms at 48 kHz).
 */
interface Rnnoise : AutoCloseable {
    /** Number of samples processed per frame (480). */
    val frameSize: Int

    /**
     * Denoises one frame of [input] into [output].
     *
     * Both arrays must have at least [frameSize] elements, and the samples are
     * expected in `-1.0..1.0`; the int16 scaling RNNoise expects is handled
     * internally.
     *
     * @return speech probability in the range [0, 1] (VAD output)
     */
    fun processFrame(input: FloatArray, output: FloatArray): Float

    /** Denoises one frame of [input] and returns the denoised frame. */
    fun processFrame(input: FloatArray): FloatArray
}
