package cn.enaium.rnnoise.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.enaium.rnnoise.createRnnoise
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test that exercises the RNNoise bindings on a real Android
 * device: the .so bundled in the AAR is loaded via System.loadLibrary and
 * the denoiser must attenuate white noise.
 */
@RunWith(AndroidJUnit4::class)
class RnnoiseDenoiseTest {

    @Test
    fun frameSizeIs480() {
        createRnnoise().use { rnnoise ->
            assertEquals(480, rnnoise.frameSize)
        }
    }

    @Test
    fun noiseSuppressionAttenuatesNoise() {
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
            var vadSum = 0f
            for (frame in 0 until 20) {
                for (i in input.indices) input[i] = random.nextFloat() * 2f - 1f
                val vad = rnnoise.processFrame(input, output)
                assertTrue("VAD out of range: $vad", vad in 0f..1f)
                vadSum += vad
                for (i in input.indices) {
                    inputEnergy += input[i] * input[i].toDouble()
                    outputEnergy += output[i] * output[i].toDouble()
                }
            }
            assertTrue(output.all { it.isFinite() })
            assertTrue(vadSum.isFinite())

            assertTrue(
                "white noise should be attenuated (in=$inputEnergy, out=$outputEnergy)",
                outputEnergy < inputEnergy * 0.5,
            )
        }
    }
}
