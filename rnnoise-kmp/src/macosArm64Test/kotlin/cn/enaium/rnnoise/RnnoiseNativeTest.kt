package cn.enaium.rnnoise

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RnnoiseNativeTest {

    @Test
    fun testInvalidModelFails() {
        val model = createRnnoiseModelFromBuffer(ByteArray(4))
        try {
            assertFailsWith<IllegalStateException> {
                createRnnoise(model)
            }
        } finally {
            model.close()
        }
    }

    @Test
    fun testTonePassesThrough() {
        createRnnoise().use { rnnoise ->
            val sampleRate = 48000.0
            val frequency = 1000.0
            val amplitude = 0.2f
            var phase = 0.0

            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)

            // A steady tone is speech-like; rnnoise should keep it nearly
            // unchanged after the initial adaptation.
            var inputEnergy = 0.0
            var outputEnergy = 0.0
            for (frame in 0 until 40) {
                for (i in input.indices) {
                    input[i] = (sin(phase) * amplitude).toFloat()
                    phase += 2 * PI * frequency / sampleRate
                }
                rnnoise.processFrame(input, output)
                for (i in input.indices) {
                    inputEnergy += input[i] * input[i].toDouble()
                    outputEnergy += output[i] * output[i].toDouble()
                }
            }

            assertTrue(
                outputEnergy > inputEnergy * 0.5,
                "tone should pass through (in=$inputEnergy, out=$outputEnergy)",
            )
        }
    }

    @Test
    fun testMultipleInstances() {
        createRnnoise().use { first ->
            createRnnoise().use { second ->
                val input = FloatArray(first.frameSize) { it * 0.001f }
                val firstOut = first.processFrame(input)
                val secondOut = second.processFrame(input)
                assertTrue(firstOut.all { it.isFinite() })
                assertTrue(secondOut.all { it.isFinite() })
            }
        }
    }
}
