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

package cn.enaium.rnnoise.examples.waveform

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import org.libsdl.app.SDLActivity

/**
 * Launcher activity hosting the waveform example.
 *
 * SDLActivity loads the app's shared library and calls its exported `SDL_main`
 * symbol on a dedicated SDL thread. `libmain.so` (built from the example KMP
 * module's androidNative source sets) links SDL3, ImGui/ImPlot, RNNoise and the
 * audio backend statically, so only `main` needs to be loaded.
 *
 * The microphone permission is requested before SDL starts; the native side
 * opens the capture device with a retry, so granting it a moment later still
 * ends up in a running pipeline. The system bars are hidden by SDL itself once
 * the example puts its window in fullscreen.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_REQUEST)
        }
        super.onCreate(savedInstanceState)
    }

    /**
     * The waveform wants the wider axis.
     *
     * SDL derives the requested orientation from the window size and the
     * (absent) `SDL_ORIENTATIONS` hint, which for a resizable window leaves it
     * at "full user" - i.e. whatever the device currently is. Both landscape
     * directions are allowed so the device can still be flipped.
     */
    override fun setOrientationBis(w: Int, h: Int, resizable: Boolean, hint: String?) {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private companion object {
        const val RECORD_AUDIO_REQUEST = 1
    }
}
