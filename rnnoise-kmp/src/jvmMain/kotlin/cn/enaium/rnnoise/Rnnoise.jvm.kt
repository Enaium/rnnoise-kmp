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
// JNI bridge – loads the native library and provides external declarations
// =========================================================================

internal object Jni {
    init {
        NativeLoader.load()
    }

    // ---- DenoiseState ----
    external fun create(modelPtr: Long): Long
    external fun destroy(ptr: Long)
    external fun getFrameSize(): Int
    external fun getSize(): Int
    external fun processFrame(ptr: Long, input: FloatArray, output: FloatArray): Float

    // ---- RNNModel ----
    external fun modelFromFilename(filename: String): Long
    external fun modelFromBuffer(buffer: ByteArray): Long
    external fun modelFree(modelPtr: Long)
}

// =========================================================================
// JVM/Android actual implementations
// =========================================================================

class JvmRnnoiseModel internal constructor(internal val ptr: Long) : RnnoiseModel {
    override fun close() {
        Jni.modelFree(ptr)
    }
}

class JvmRnnoise internal constructor(internal val ptr: Long) : Rnnoise {

    override val frameSize: Int = Jni.getFrameSize()

    override fun processFrame(input: FloatArray, output: FloatArray): Float {
        require(input.size == output.size) {
            "input and output frame lengths must match"
        }
        return Jni.processFrame(ptr, input, output)
    }

    override fun processFrame(input: FloatArray): FloatArray {
        val output = FloatArray(frameSize)
        processFrame(input, output)
        return output
    }

    override fun close() {
        Jni.destroy(ptr)
    }
}

// =========================================================================
// actual factory functions
// =========================================================================

actual fun createRnnoise(model: RnnoiseModel?): Rnnoise {
    val modelPtr = (model as? JvmRnnoiseModel)?.ptr ?: 0L
    val ptr = Jni.create(modelPtr)
    check(ptr != 0L) { "rnnoise_create failed (invalid model?)" }
    return JvmRnnoise(ptr)
}

actual fun createRnnoiseModelFromFilename(filename: String): RnnoiseModel =
    JvmRnnoiseModel(Jni.modelFromFilename(filename))

actual fun createRnnoiseModelFromBuffer(buffer: ByteArray): RnnoiseModel =
    JvmRnnoiseModel(Jni.modelFromBuffer(buffer))
