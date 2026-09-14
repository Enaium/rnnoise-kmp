package cn.enaium.rnnoise

import kotlin.math.PI
import kotlin.math.sin
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
    fun testLowLevelNoiseIsAttenuated() {
        // Only holds because processFrame scales the -1..1 frame into the int16
        // range RNNoise is trained on and scales the result back: without it the
        // frame never clears RNNoise's silence gate and comes back unchanged.
        createRnnoise().use { rnnoise ->
            val random = Random(7)
            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)
            val amplitude = 0.0316f // ~-30 dBFS

            fun fillNoise() {
                for (i in input.indices) input[i] = (random.nextFloat() * 2f - 1f) * amplitude
            }

            // Let the model adapt to the noise statistics first.
            for (frame in 0 until 20) {
                fillNoise()
                rnnoise.processFrame(input, output)
            }

            var inputEnergy = 0.0
            var outputEnergy = 0.0
            for (frame in 0 until 20) {
                fillNoise()
                rnnoise.processFrame(input, output)
                for (i in input.indices) {
                    inputEnergy += input[i] * input[i].toDouble()
                    outputEnergy += output[i] * output[i].toDouble()
                }
            }

            assertTrue(
                outputEnergy < inputEnergy * 0.25,
                "noise around -30 dBFS should be attenuated too " +
                    "(in=$inputEnergy, out=$outputEnergy)",
            )
        }
    }

    @Test
    fun testSpeechLikeContentIsKept() {
        // The other half of the contract: suppressing noise must not suppress a
        // voice. The signal is a harmonic stack with vibrato, which RNNoise
        // reads as speech, unlike a steady tone.
        createRnnoise().use { rnnoise ->
            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)
            var phase = 0.0
            var time = 0.0

            fun fillVowel() {
                for (i in input.indices) {
                    val fundamental = 120.0 * (1.0 + 0.03 * sin(2 * PI * 5.0 * time))
                    phase += 2 * PI * fundamental / 48_000.0
                    time += 1.0 / 48_000.0
                    var sum = 0.0
                    for (harmonic in 1..20) sum += sin(phase * harmonic) / harmonic
                    input[i] = (sum / 2.2).toFloat()
                }
            }

            for (frame in 0 until 20) {
                fillVowel()
                rnnoise.processFrame(input, output)
            }

            var inputEnergy = 0.0
            var outputEnergy = 0.0
            for (frame in 0 until 20) {
                fillVowel()
                rnnoise.processFrame(input, output)
                for (i in input.indices) {
                    inputEnergy += input[i] * input[i].toDouble()
                    outputEnergy += output[i] * output[i].toDouble()
                }
            }

            assertTrue(
                outputEnergy > inputEnergy * 0.3,
                "speech-like content should survive " +
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
