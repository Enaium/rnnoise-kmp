package cn.enaium.rnnoise

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
