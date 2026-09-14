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

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package cn.enaium.rnnoise

import kotlinx.cinterop.*
import rnnoise.*

// =========================================================================
// Native (cinterop) actual implementations
// =========================================================================

class NativeRnnoiseModel internal constructor(
    internal val ptr: CPointer<RNNModel>,
    internal val ownedBuffer: COpaquePointer? = null,
) : RnnoiseModel {
    override fun close() {
        rnnoise_model_free(ptr)
        ownedBuffer?.let { nativeHeap.free(it) }
    }
}

class NativeRnnoise internal constructor(internal val ptr: CPointer<DenoiseState>) : Rnnoise {

    override val frameSize: Int = rnnoise_get_frame_size()

    // RNNoise is trained and gated on the int16 sample range, so the -1..1
    // frame is scaled up on the way in and back on the way out. The scaled copy
    // keeps the caller's array untouched, and living here keeps processFrame
    // allocation free (instances are single threaded, like the denoiser itself).
    private val scaledInput = FloatArray(frameSize)

    override fun processFrame(input: FloatArray, output: FloatArray): Float {
        require(input.size == output.size) {
            "input and output frame lengths must match"
        }

        val inCount = minOf(input.size, frameSize)
        for (i in 0 until inCount) {
            scaledInput[i] = input[i] * RNNOISE_SAMPLE_SCALE
        }

        val vad = scaledInput.usePinned { inPinned ->
            output.usePinned { outPinned ->
                rnnoise_process_frame(ptr, outPinned.addressOf(0), inPinned.addressOf(0))
            }
        }

        val outCount = minOf(output.size, frameSize)
        for (i in 0 until outCount) {
            output[i] /= RNNOISE_SAMPLE_SCALE
        }
        return vad
    }

    override fun processFrame(input: FloatArray): FloatArray {
        val output = FloatArray(frameSize)
        processFrame(input, output)
        return output
    }

    override fun close() {
        rnnoise_destroy(ptr)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createRnnoise(model: RnnoiseModel?): Rnnoise {
    val modelPtr = (model as? NativeRnnoiseModel)?.ptr
    val ptr = rnnoise_create(modelPtr)
        ?: error("rnnoise_create returned null")
    return NativeRnnoise(ptr)
}

actual fun createRnnoiseModelFromFilename(filename: String): RnnoiseModel {
    val ptr = rnnoise_model_from_filename(filename)
        ?: error("rnnoise_model_from_filename returned null: $filename")
    return NativeRnnoiseModel(ptr)
}

actual fun createRnnoiseModelFromBuffer(buffer: ByteArray): RnnoiseModel {
    // rnnoise_model_from_buffer keeps a pointer to the caller's buffer, so
    // the bytes are copied to the native heap and freed when the model is
    // closed.
    val copy = nativeHeap.allocArray<ByteVar>(buffer.size.toLong())
    for (i in buffer.indices) {
        copy[i] = buffer[i]
    }
    val ptr = rnnoise_model_from_buffer(copy, buffer.size)
    if (ptr == null) {
        nativeHeap.free(copy)
        error("rnnoise_model_from_buffer returned null")
    }
    // Upstream rnnoise_model_from_buffer() does not initialize the `file`
    // member of RNNModel; rnnoise_model_free() would then call fclose() on
    // garbage. Clear it to work around the bug (see cinterop_helpers.h).
    ptr.pointed.file = null
    return NativeRnnoiseModel(ptr, copy)
}
