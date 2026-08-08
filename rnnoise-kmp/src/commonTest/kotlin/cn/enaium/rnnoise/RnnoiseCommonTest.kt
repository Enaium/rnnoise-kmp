package cn.enaium.rnnoise

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RnnoiseCommonTest {

    @Test
    fun testFrameSize() {
        createRnnoise().use { rnnoise ->
            assertEquals(480, rnnoise.frameSize)
        }
    }

    @Test
    fun testProcessSilence() {
        createRnnoise().use { rnnoise ->
            val input = FloatArray(rnnoise.frameSize)
            val output = rnnoise.processFrame(input)
            assertEquals(input.size, output.size)
            assertTrue(output.all { it.isFinite() })
        }
    }

    @Test
    fun testVadProbabilityInRange() {
        createRnnoise().use { rnnoise ->
            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)
            for (frame in 0 until 10) {
                val vadProb = rnnoise.processFrame(input, output)
                assertTrue(vadProb in 0f..1f, "VAD probability out of range: $vadProb")
                assertTrue(output.all { it.isFinite() })
            }
        }
    }

    @Test
    fun testNoiseAttenuation() {
        createRnnoise().use { rnnoise ->
            val random = Random(42)
            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)

            // Let the model adapt to the noise statistics first.
            for (frame in 0 until 20) {
                for (i in input.indices) input[i] = random.nextFloat() * 2f - 1f
                rnnoise.processFrame(input, output)
            }

            var inputEnergy = 0.0
            var outputEnergy = 0.0
            for (frame in 0 until 20) {
                for (i in input.indices) input[i] = random.nextFloat() * 2f - 1f
                rnnoise.processFrame(input, output)
                for (i in input.indices) {
                    inputEnergy += input[i] * input[i].toDouble()
                    outputEnergy += output[i] * output[i].toDouble()
                }
            }

            assertTrue(
                outputEnergy < inputEnergy * 0.5,
                "white noise should be attenuated " +
                    "(in=$inputEnergy, out=$outputEnergy)",
            )
        }
    }

    @Test
    fun testCreateAndCloseManyModels() {
        // Creating and freeing many buffer-backed models reuses native heap
        // blocks; the upstream RNNModel.file initialization bug makes the
        // free path crash when a reused block is not zeroed.
        repeat(50) {
            createRnnoiseModelFromBuffer(ByteArray(4)).close()
        }
    }
}
