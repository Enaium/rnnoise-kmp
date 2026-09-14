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

import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags

/**
 * Boots an SDL3 window with the 2D renderer, the published imgui-kmp SDL
 * backends and an ImPlot context, then runs the frame loop calling [draw].
 */
object ImGuiSdlApp {

    /**
     * Runs until the window is closed or [frames] frames were drawn; returns
     * `false` when no window could be opened at all.
     *
     * `--frames` is passed through from the entry points to bound the run.
     */
    fun run(title: String, frames: Int = Int.MAX_VALUE, draw: (frame: Int) -> Unit): Boolean {
        SDL.setMainReady()
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            // No silent fallback to SDL's dummy driver: rendering into an
            // invisible window looks exactly like a broken app. Headless runs
            // set SDL_VIDEO_DRIVER=dummy, which SDL picks up by itself.
            println("SDL_Init failed: ${SDL.error()}$videoInitHint")
            return false
        }
        val version = SDL.version()
        println("SDL ${version.major}.${version.minor}.${version.micro} (${SDL.revision()})")
        println("video driver: ${SDL.getCurrentVideoDriver()}")

        SDL.createWindow(
            title = title,
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        ).use { window ->
            // Android: a fullscreen SDL window is what makes SDLActivity hide
            // the status and navigation bars that would draw over the UI.
            if (windowStartsFullscreen) window.fullscreen = true
            SDL.createRenderer(window).use { renderer ->
                val context = ImGui.createContext()
                try {
                    val platform = ImGuiSdlBackend(window)
                    val renderBackend = ImGuiSdlRendererBackend(renderer)
                    platform.init()

                    // Bake the font atlas at the display's pixel density so the
                    // text stays crisp on Retina screens.
                    val fonts = ImGui.getIO().fonts
                    val density = maxOf(platform.framebufferScale.x, platform.framebufferScale.y, 1f)
                    fonts.addFontDefault(
                        ImFontConfig(sizePixels = 13f * density, rasterizerDensity = density),
                    )
                    check(fonts.build()) { "font atlas build failed" }
                    val texData = fonts.getTexDataAsRGBA32()
                    fonts.setTexID(
                        renderBackend.uploadFontTexture(texData.pixels, texData.width, texData.height),
                    )

                    // ImPlot keeps its own context, bound to the imgui one.
                    val plotContext = ImPlot.createContext()
                    ImPlot.setImGuiContext(ImGui.getCurrentContext() ?: error("no imgui context"))

                    var running = true
                    var frame = 0
                    while (running && frame < frames) {
                        while (true) {
                            val event = SDL.pollEvent() ?: break
                            when (event) {
                                is SDLEvent.Quit -> running = false
                                is SDLEvent.Window ->
                                    if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false

                                else -> platform.processEvent(event)
                            }
                        }

                        platform.newFrame()
                        draw(frame)
                        ImGui.render()

                        renderer.drawColor = SDLColor(18, 18, 24)
                        renderer.clear()
                        renderBackend.renderDrawData(ImGui.getDrawData())
                        renderer.present()
                        frame++
                    }

                    ImPlot.destroyContext(plotContext)
                    renderBackend.close()
                } finally {
                    ImGui.destroyContext(context)
                }
            }
        }
        SDL.quit()
        return true
    }
}
