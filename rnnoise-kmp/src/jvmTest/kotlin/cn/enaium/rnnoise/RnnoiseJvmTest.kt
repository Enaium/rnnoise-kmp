package cn.enaium.rnnoise

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class RnnoiseJvmTest {

    @Test
    fun testCreateModelFromBuffer() {
        val model = createRnnoiseModelFromBuffer(ByteArray(4))
        try {
            // Garbage weights must not crash the JVM; creation fails cleanly.
            assertThrows(IllegalStateException::class.java) {
                createRnnoise(model)
            }
        } finally {
            model.close()
        }
    }

    @Test
    fun testCreateAndCloseManyModels() {
        // See RnnoiseCommonTest.testCreateAndCloseManyModels.
        repeat(100) {
            createRnnoiseModelFromBuffer(ByteArray(4)).close()
        }
    }

    @Test
    fun testProcessMultipleFrames() {
        createRnnoise().use { rnnoise ->
            val input = FloatArray(rnnoise.frameSize)
            val output = FloatArray(rnnoise.frameSize)
            var vadSum = 0f
            for (frame in 0 until 100) {
                vadSum += rnnoise.processFrame(input, output)
            }
            assertTrue(vadSum.isFinite())
        }
    }
}
