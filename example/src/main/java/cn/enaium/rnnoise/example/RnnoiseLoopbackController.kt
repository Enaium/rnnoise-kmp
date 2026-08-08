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

package cn.enaium.rnnoise.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.enaium.rnnoise.Rnnoise
import cn.enaium.rnnoise.createRnnoise
import kotlin.concurrent.thread

/**
 * Owns the real-time RNNoise loopback pipeline and exposes observable Compose
 * state that the UI collects:
 *
 *  - The microphone is captured via [AudioRecord] at 48 kHz (the sample rate
 *    RNNoise is designed for).
 *  - Each 10 ms frame is fed to [Rnnoise.processFrame], which removes the
 *    stationary background noise and returns the speech probability (VAD).
 *  - The denoised frame is played back through [AudioTrack].
 *
 *  With the "noise suppression" switch off, the raw microphone signal is
 *  played back instead, so the difference is audible.
 */
class RnnoiseLoopbackController {

    companion object {
        private const val TAG = "RNNoiseExample"
        // RNNoise processes 10 ms frames at 48 kHz.
        private const val SAMPLE_RATE = 48000
        private const val FRAME_SAMPLES = 480
    }

    // ---- Compose-observable state ----
    var isRunning by mutableStateOf(false)
        private set
    var noiseSuppressionEnabled by mutableStateOf(true)
    var vadProbability by mutableFloatStateOf(0f)
    var status by mutableStateOf("Idle")
    var error by mutableStateOf<String?>(null)
        private set

    // ---- Audio ----
    private var rnnoise: Rnnoise? = null
    private var audioRecord: AudioRecord? = null
    private var outputTrack: AudioTrack? = null

    private var worker: Thread? = null

    fun toggle() {
        if (isRunning) stop() else start()
    }

    fun start() {
        try {
            rnnoise = createRnnoise()
            initAudio()
            isRunning = true
            error = null
            status = "Running…"
            worker = thread(start = true) { processingLoop() }
        } catch (t: Throwable) {
            status = "Failed to start: ${t.javaClass.simpleName}: ${t.message}"
            stop()
        }
    }

    fun stop() {
        isRunning = false
        worker?.join(500)
        worker = null

        runCatching { audioRecord?.stop() }
        runCatching { outputTrack?.stop() }

        audioRecord?.release()
        audioRecord = null
        outputTrack?.release()
        outputTrack = null

        rnnoise?.close()
        rnnoise = null

        if (status == "Running…") status = "Stopped"
    }

    // =========================================================================
    // Audio I/O
    // =========================================================================

    private fun initAudio() {
        val minRecordBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minRecordBuf, FRAME_SAMPLES * 2),
        )

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        val minTrackBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        outputTrack = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minTrackBuf, FRAME_SAMPLES * 8))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // =========================================================================
    // Processing loop
    // =========================================================================

    private fun processingLoop() {
        val denoiser = rnnoise ?: return
        val record = audioRecord ?: return
        val track = outputTrack ?: return

        val micFrame = ShortArray(FRAME_SAMPLES)
        val outFrame = ShortArray(FRAME_SAMPLES)
        val inputFloat = FloatArray(FRAME_SAMPLES)
        val outputFloat = FloatArray(FRAME_SAMPLES)

        var frameCount = 0

        try {
            record.startRecording()
            track.play()

            while (isRunning) {
                val read = record.read(micFrame, 0, FRAME_SAMPLES)
                if (read <= 0) continue
                val n = minOf(read, FRAME_SAMPLES)

                // 1. Convert the PCM16 mic frame to float.
                for (i in 0 until n) {
                    inputFloat[i] = micFrame[i] / 32768.0f
                }

                // 2. Denoise (or bypass when the switch is off). The return
                //    value is the speech probability in [0, 1].
                val vad: Float = if (noiseSuppressionEnabled) {
                    denoiser.processFrame(inputFloat, outputFloat)
                } else {
                    outputFloat.copyFrom(inputFloat, n)
                    -1f
                }

                // 3. Play back the denoised (or raw) result.
                for (i in 0 until n) {
                    var s = outputFloat[i] * 32767
                    if (s > 32767f) s = 32767f
                    if (s < -32768f) s = -32768f
                    outFrame[i] = s.toInt().toShort()
                }
                track.write(outFrame, 0, n)

                frameCount++
                if (frameCount % 50 == 0) { // every 500 ms
                    if (vad >= 0f) {
                        vadProbability = vad
                        status = "VAD: %.0f%%".format(vad * 100)
                    } else {
                        status = "Bypass (raw mic)"
                    }
                    Log.i(
                        TAG,
                        "RNNoise status: vad=${"%.3f".format(vadProbability)} frames=$frameCount",
                    )
                }
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "RNNoise processing error", t)
        } finally {
            runCatching { track.pause() }
            runCatching { record.stop() }
        }
    }

    private fun FloatArray.copyFrom(src: FloatArray, n: Int) {
        for (i in 0 until n) this[i] = src[i]
    }
}
