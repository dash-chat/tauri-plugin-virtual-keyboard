package org.dashchat.virtualkeyboard

import android.view.View
import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Plugin
import app.tauri.plugin.Invoke
import androidx.core.graphics.Insets
import androidx.core.view.OnApplyWindowInsetsListener
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@TauriPlugin
class VirtualKeyboardPlugin(private val activity: Activity): Plugin(activity) {
    private var webView: WebView? = null
    private var animating = false
    private var imeVisible = false

    // The keyboard events go straight into the page: the Tauri plugin event
    // channel delivers with hundreds of ms of latency, so a willShow sent
    // through it arrives after the IME animation it announces has already
    // finished. A direct evaluation lands within a frame or two — early
    // enough for the page to glide its layout in sync with the animation.
    // Runs on the UI thread (the insets callbacks' thread).
    private fun emit(name: String, json: String) {
        webView?.evaluateJavascript(
            "window.__VIRTUAL_KEYBOARD_EVENT__ && window.__VIRTUAL_KEYBOARD_EVENT__('$name', $json)",
            null)
    }

    override fun load(webView: WebView) {
        super.load(webView)
        this.webView = webView
        val rootView = activity.window.decorView
        val density = activity.resources.displayMetrics.density

        // While the IME is up, Chromium lets touch drags pan the visual viewport
        // (the webview is full-size underneath the keyboard), showing a huge
        // scrollbar and dragging the page out of place. The page lays itself out
        // against the inset instead, so pin the native scroll and never draw the
        // webview's own scrollbars — all real scrolling is DOM-level.
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            if (imeVisible && (scrollY != 0 || v.scrollX != 0)) {
                v.scrollTo(0, 0)
            }
        }

        // Chromium derives env(safe-area-inset-*) from the insets dispatched to
        // the WebView, and zeroes the bottom safe area while the IME is up —
        // mid-animation, one frame before willShow reaches JS — so any
        // env()-based padding visible near the keyboard collapses and jumps.
        // The page lays itself out against --keyboard-inset-height instead, so
        // give the webview an IME-less view of the world: strip the IME from
        // the insets it receives and block IME animation callbacks from
        // propagating into it. The decor listeners below still see the real
        // insets and feed the keyboard events.
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val stripped = WindowInsetsCompat.Builder(insets)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
                .setVisible(WindowInsetsCompat.Type.ime(), false)
                .build()
            ViewCompat.onApplyWindowInsets(v, stripped)
        }
        ViewCompat.setWindowInsetsAnimationCallback(webView, object :
            WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP
            ) {
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat = insets
        })

        // The webview keeps its full size and the IME overlays it; the page lays
        // itself out against the --keyboard-inset-height CSS variable the guest-js side
        // maintains from the keyboard events emitted here.
        ViewCompat.setOnApplyWindowInsetsListener(rootView,
            OnApplyWindowInsetsListener { _: View?, windowInsets: WindowInsetsCompat? ->
                val ime = windowInsets!!.getInsets(WindowInsetsCompat.Type.ime())
                imeVisible = ime.bottom > 0

                if (ime.bottom > 0) {
                    webView.scrollTo(0, 0)
                }

                // Animated changes are reported by the willShow/willHide pair
                // below; this covers IMEs/settings where no animation runs.
                if (!animating) {
                    emit("change", "{\"height\":${imeOverlap(rootView, ime.bottom) / density}}")
                }

                windowInsets
            })

        ViewCompat.setWindowInsetsAnimationCallback(rootView, object :
            WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE
            ) {
            override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                    animating = true
                }
            }

            override fun onStart(
                animation: WindowInsetsAnimationCompat,
                bounds: WindowInsetsAnimationCompat.BoundsCompat
            ): WindowInsetsAnimationCompat.BoundsCompat {
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                    // The end-state insets are already dispatched by onStart, so
                    // the root insets carry the animation's target height.
                    val target = imeHeight(rootView) / density
                    if (target > 0) {
                        emit("willShow", "{\"height\":$target,\"durationMs\":${animation.durationMillis}}")
                    } else {
                        emit("willHide", "{\"durationMs\":${animation.durationMillis}}")
                    }
                }
                return bounds
            }

            // Required override. The per-frame insets are deliberately ignored:
            // --keyboard-inset-height is set once from the target height reported by
            // onStart, and the guest-js side glides the layout on the compositor.
            override fun onProgress(
                insets: WindowInsetsCompat,
                runningAnimations: List<WindowInsetsAnimationCompat>
            ): WindowInsetsCompat = insets

            override fun onEnd(animation: WindowInsetsAnimationCompat) {
                if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                    animating = false
                    val height = imeHeight(rootView) / density
                    if (height > 0) {
                        emit("didShow", "{\"height\":$height}")
                    } else {
                        emit("didHide", "{}")
                    }
                }
            }
        })
    }

    private fun imeHeight(rootView: View): Int =
        imeOverlap(rootView, ViewCompat.getRootWindowInsets(rootView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)

    // The page measures the keyboard from the webview's bottom edge, which the
    // host app may lift above the navigation bar by padding the webview's parent.
    private fun imeOverlap(rootView: View, imeBottom: Int): Int {
        val webView = webView ?: return imeBottom
        val location = IntArray(2)
        webView.getLocationInWindow(location)
        val gapBelowWebView = rootView.height - (location[1] + webView.height)
        return maxOf(0, imeBottom - gapBelowWebView)
    }

    @Command
    fun hide(invoke: Invoke) {
        activity.runOnUiThread {
            webView?.let {
                WindowInsetsControllerCompat(activity.window, it)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            invoke.resolve()
        }
    }

    @Command
    fun show(invoke: Invoke) {
        activity.runOnUiThread {
            webView?.let {
                WindowInsetsControllerCompat(activity.window, it)
                    .show(WindowInsetsCompat.Type.ime())
            }
            invoke.resolve()
        }
    }
}
