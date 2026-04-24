package com.example.mirror.webview.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.lifecycleScope
import com.example.mirror.webview.R
import com.example.mirror.webview.databinding.LoadingViewBinding
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseEventActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import com.ffalcon.mercury.android.sdk.ui.wiget.MirrorContainerView
import com.ffalcon.mercury.android.sdk.util.FLogger
import com.ffalcon.mercury.android.sdk.util.MyTouchUtils

/**
 * MirrorWebViewActivity - WebView + MirroringView binocular mirroring solution.
 *
 * Only one WebView is created (left side); the right side uses MirroringView for
 * pixel-level mirroring.
 * Advantages:
 *  - Single JS runtime, no state synchronization issues
 *  - Existing H5 pages can be displayed binocularly on AR glasses with zero modifications
 *  - All native calls are naturally executed only once
 *  - JS SDK only needs the simplest TP interaction capability
 */
class MirrorWebViewActivity : BaseEventActivity() {

    private lateinit var webView: WebView
    private lateinit var mirrorView: MirroringWebView
    private lateinit var loadingView: MirrorContainerView
    private lateinit var mirrorWeb: View

    /**
     * Controls whether TP touch events are dispatched to the WebView.
     * When set to false, TP events are still handled by BaseEventActivity (gesture recognition),
     * but will not be forwarded to the WebView (i.e., the web page will not scroll).
     */
    var isTouchDispatchEnabled = false

    companion object {
        /** Default URL to load; can be overridden via Intent extra */
        const val EXTRA_URL = "extra_url"
        private const val DEFAULT_URL = "file:///android_asset/demo_webview.html"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror_webview)
        mirrorWeb = findViewById(R.id.rootLayout)
        webView = findViewById(R.id.webView)
        mirrorView = findViewById(R.id.mirrorView)
        loadingView = findViewById(R.id.loading_layout)
        loadingView.bindTo(LoadingViewBinding::class.java)

        setupWebView()
        loadUrl()
        // mirrorView starts invisible; mirroring begins after JS callback in onPageFinished
        observeTempleActions()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        // Set WebView background to black as a dark mode fallback.
        // Even if JS injection fails (e.g., on error pages), the background stays black.
        webView.setBackgroundColor(Color.BLACK)
        webView.webViewClient = object : WebViewClient() {
            // Ensure all links load inside the WebView instead of launching an external browser.
            // This allows WebView to properly maintain browsing history so canGoBack() works.
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                FLogger.d("WebView internal navigation: $url")
                view?.loadUrl(url)
                return true
            }

            // Called when page loading starts
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                loadingView.visibility = View.VISIBLE
                mirrorWeb.visibility = View.INVISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject dark mode + focus management JS via WebViewInjector
                view?.let { wv ->
                    WebViewInjector.inject(wv) {
                        // JS execution complete; WebView is now dark and safe to display
                        loadingView.visibility = View.GONE
                        mirrorWeb.visibility = View.VISIBLE
                        // MirroringWebView is based on TextureView; after becoming VISIBLE,
                        // SurfaceTexture is created asynchronously. Use post to delay to the
                        // next frame, ensuring layout is complete and SurfaceTexture is ready.
                        mirrorView.post {
                            startMirroring()
                        }
                    }
                }
            }

            // Called on page load error (API >= 23)
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                // Only handle main frame errors; ignore sub-resource errors
                val isMainFrame = request?.isForMainFrame ?: false
                if (isMainFrame) {
                    // Inject dark mode on error pages too, so error text appears as light on dark.
                    // WebView background is already set to black (fallback); JS handles text color.
                    view?.let { wv ->
                        WebViewInjector.inject(wv) {
                            loadingView.visibility = View.GONE
                            mirrorWeb.visibility = View.VISIBLE
                            mirrorView.post { startMirroring() }
                        }
                    } ?: run {
                        loadingView.visibility = View.GONE
                    }
                }
            }
        }
        webView.addJavascriptInterface(WebBridge(), "MercuryBridge")
    }

    /**
     * JS Bridge — provides native capabilities for JS in the WebView to call.
     * Only one WebView exists, so no isLeft distinction is needed.
     */
    inner class WebBridge {
        @JavascriptInterface
        fun showToast(msg: String) {
            runOnUiThread { FToast.show(msg) }
        }

        @JavascriptInterface
        fun finish() {
            runOnUiThread { this@MirrorWebViewActivity.finish() }
        }
    }

    private fun loadUrl() {
        val url = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_URL
        webView.loadUrl(url)
    }

    /**
     * Start MirroringView mirroring
     */
    private fun startMirroring() {
        mirrorView.setSource(webView)
        mirrorView.startMirroring()
    }

    /**
     * Observe TempleActions and forward them to the focus management system in the WebView.
     * - Click: triggers the native onclick of the currently focused element
     * - SlideForward: moves focus to the next clickable element
     * - SlideBackward: moves focus to the previous clickable element
     * - DoubleClick: navigates back in WebView history or finishes the activity
     */
    private fun observeTempleActions() {
        lifecycleScope.launchWhenResumed {
            templeActionViewModel.state.collect { action ->
                // DoubleClick: go back in WebView history first; finish if cannot go back
                if (action is TempleAction.DoubleClick) {
                    val canBack = webView.canGoBack()
                    FLogger.d("DoubleClick: canGoBack=$canBack, currentUrl=${webView.url}")
                    if (canBack) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                    return@collect
                }

                // Other events: dispatch to page JS via the __mercuryFocus system
                val js = buildJsDispatchCall(action)
                if (js != null) {
                    webView.evaluateJavascript(js, null)
                    // Delay mirror refresh to ensure left/right sync
                    refreshMirrorDelayed()
                    // Smooth scroll animation requires an additional delayed refresh
                    // to ensure mirror is synced after the scroll animation completes
                    refreshMirrorDelayed(350)
                }
            }
        }
    }

    /**
     * Delay mirror frame refresh to ensure the WebView finishes rendering after
     * JS executes scrollIntoView, keeping left and right displays in sync.
     */
    private fun refreshMirrorDelayed(delayMs: Long = 50) {
        webView.postDelayed({
            mirrorView.refreshFrame()
        }, delayMs)
    }

    /**
     * Convert a TempleAction to a JS call string.
     * Uses the __mercuryFocus system injected in onPageFinished.
     */
    private fun buildJsDispatchCall(action: TempleAction): String? {
        val js = when (action) {
            is TempleAction.Click -> "__mercuryFocus.clickFocused();"
            is TempleAction.SlideForward, is TempleAction.SlideDownwards -> "__mercuryFocus.moveFocus(1);"
            is TempleAction.SlideBackward, is TempleAction.SlideUpwards -> "__mercuryFocus.moveFocus(-1);"
            else -> return null
        }
        return """
            (function(){
                if(window.__mercuryFocus){
                    $js
                }
            })();
        """.trimIndent()
    }

    /**
     * Intercept touch event dispatching: when isTouchDispatchEnabled is false,
     * prevent TP touch events from reaching the WebView while still preserving
     * gesture recognition capability.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!isTouchDispatchEnabled && MyTouchUtils.isRight(
                event
            )
        ) {
            // Only perform gesture recognition via onTouchEvent; do not let events reach WebView
            onTouchEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onDestroy() {
        mirrorView.stopMirroring()
        webView.destroy()
        super.onDestroy()
    }
}
