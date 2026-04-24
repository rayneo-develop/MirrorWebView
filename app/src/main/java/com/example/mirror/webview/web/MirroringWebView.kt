package com.example.mirror.webview.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import android.webkit.WebView
import java.lang.ref.WeakReference

/**
 * TextureView that mirrors a WebView.
 * Displays the content of another WebView in real time.
 */
class MirroringWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "MirroringWebView"
        private const val DEFAULT_FPS = 30
    }

    // Weak reference to the source WebView to avoid memory leaks
    private var sourceWebViewRef: WeakReference<WebView>? = null
    private var sourceWebView: WebView?
        get() = sourceWebViewRef?.get()
        set(value) {
            sourceWebViewRef = value?.let { WeakReference(it) }
        }

    // Mirroring state
    private var isMirroring = false
    private var isPaused = false
    private var frameCount = 0
    private var lastFrameTime = 0L

    // Performance configuration
    private var targetFps = DEFAULT_FPS
    private var frameDelayMs = 1000L / DEFAULT_FPS
    private var quality = 80

    // Rendering-related
    private var mirrorBitmap: Bitmap? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mirrorRunnable: Runnable? = null

    // Capture method selection
    private var captureMethod = CaptureMethod.AUTO

    enum class CaptureMethod {
        AUTO,       // Automatically choose the best method
        DRAW,       // Use draw(canvas) method (recommended default)
        BITMAP      // Use createBitmap method
    }

    // Callback interface
    interface MirroringCallback {
        fun onMirroringStarted()
        fun onMirroringStopped()
        fun onFrameCaptured(fps: Float)
        fun onError(error: String)
    }

    private var callback: MirroringCallback? = null

    private val surfaceTextureListenerInstance = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            Log.d(TAG, "Surface available, restarting mirroring if needed")
            if (isMirroring && !isPaused) {
                startMirroring()
            }
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
            Log.d(TAG, "Surface size changed: ${width}x${height}")
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            Log.d(TAG, "Surface destroyed")
            stopMirroring()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // No action needed
        }
    }

    init {
        surfaceTextureListener = surfaceTextureListenerInstance
    }

    /**
     * Set the source WebView
     */
    fun setSource(webView: WebView) {
        sourceWebView = webView
        Log.d(TAG, "Source WebView set")
    }

    /**
     * Set target frame rate (FPS)
     */
    fun setTargetFps(fps: Int) {
        targetFps = fps.coerceIn(1, 60)
        frameDelayMs = 1000L / targetFps
        Log.d(TAG, "Target FPS set to: $targetFps, delay: ${frameDelayMs}ms")
    }

    /**
     * Set capture quality (1-100)
     */
    fun setQuality(quality: Int) {
        this.quality = quality.coerceIn(1, 100)
    }

    /**
     * Set capture method
     */
    fun setCaptureMethod(method: CaptureMethod) {
        this.captureMethod = method
    }

    /**
     * Set callback
     */
    fun setMirroringCallback(callback: MirroringCallback) {
        this.callback = callback
    }

    /**
     * Start mirroring
     */
    fun startMirroring() {
        if (isMirroring) {
            Log.w(TAG, "Mirroring already started")
            return
        }

        val source = sourceWebView
        if (source == null) {
            callback?.onError("Source WebView is null")
            Log.e(TAG, "Cannot start mirroring: source WebView is null")
            return
        }

        if (!isAvailable) {
            Log.w(TAG, "TextureView surface not available, will start when ready")
            post {
                if (isAvailable) {
                    startMirroringInternal()
                }
            }
            return
        }

        startMirroringInternal()
    }

    private fun startMirroringInternal() {
        isMirroring = true
        isPaused = false
        frameCount = 0
        lastFrameTime = System.currentTimeMillis()

        mirrorRunnable = Runnable {
            if (isMirroring && !isPaused && isAvailable) {
                captureFrame()
                mainHandler.postDelayed(mirrorRunnable!!, frameDelayMs)
            }
        }

        mainHandler.post(mirrorRunnable!!)
        callback?.onMirroringStarted()
        Log.d(TAG, "Mirroring started with FPS: $targetFps")
    }

    /**
     * Stop mirroring
     */
    fun stopMirroring() {
        isMirroring = false
        mirrorRunnable?.let { mainHandler.removeCallbacks(it) }
        mirrorRunnable = null

        // Clean up resources
        mirrorBitmap?.recycle()
        mirrorBitmap = null

        callback?.onMirroringStopped()
        Log.d(TAG, "Mirroring stopped")
    }

    /**
     * Pause mirroring (temporarily stop without releasing resources)
     */
    fun pauseMirroring() {
        isPaused = true
        Log.d(TAG, "Mirroring paused")
    }

    /**
     * Resume mirroring
     */
    fun resumeMirroring() {
        if (isPaused && isMirroring) {
            isPaused = false
            mirrorRunnable?.let { mainHandler.post(it) }
            Log.d(TAG, "Mirroring resumed")
        }
    }

    /**
     * Capture the current frame
     */
    private fun captureFrame() {
        val source = sourceWebView
        if (source == null || source.width == 0 || source.height == 0) {
            return
        }

        val startTime = System.currentTimeMillis()

        try {
            val bitmap = when (captureMethod) {
                CaptureMethod.DRAW -> captureWithDraw(source)
                CaptureMethod.BITMAP -> captureWithBitmap(source)
                CaptureMethod.AUTO -> captureWithAuto(source)
            }

            if (bitmap != null && !bitmap.isRecycled) {
                updateTextureView(bitmap)
                updateFrameStats(startTime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture frame", e)
            callback?.onError("Frame capture failed: ${e.message}")
        }
    }

    /**
     * Capture using draw(canvas) (recommended method)
     */
    private fun captureWithDraw(source: WebView): Bitmap? {
        // Ensure WebView is in software rendering mode (set only once to avoid rendering issues from per-frame switching)
        if (source.layerType != LAYER_TYPE_SOFTWARE) {
            source.setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        return try {
            val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // WebView.draw(canvas) automatically accounts for scroll offset,
            // causing content to be drawn outside Bitmap bounds (resulting in black screen).
            // Reverse-translate the canvas to compensate, ensuring the visible area is drawn at (0,0).
            canvas.save()
            canvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
            source.draw(canvas)
            canvas.restore()

            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Draw capture failed", e)
            null
        }
    }

    /**
     * Capture using createBitmap (fallback method)
     */
    private fun captureWithBitmap(source: WebView): Bitmap? {
        return try {
            source.isDrawingCacheEnabled = true
            val drawingCache = source.drawingCache
            val result = if (drawingCache != null && !drawingCache.isRecycled) {
                Bitmap.createBitmap(drawingCache)
            } else {
                captureWithDraw(source) // Fall back to draw method
            }
            source.isDrawingCacheEnabled = false
            result
        } catch (e: Exception) {
            Log.e(TAG, "Bitmap capture failed", e)
            captureWithDraw(source) // Fall back to draw method
        }
    }

    /**
     * Auto-select best capture method (uses optimized version with Bitmap reuse)
     */
    private fun captureWithAuto(source: WebView): Bitmap? {
        return captureWithOptimizedDraw(source)
    }

    /**
     * Optimized capture: reuse cached Bitmap to reduce memory allocation
     */
    private fun captureWithOptimizedDraw(source: WebView): Bitmap? {
        val width = source.width
        val height = source.height

        if (width <= 0 || height <= 0) return null

        // Reuse Bitmap to reduce GC
        if (mirrorBitmap == null ||
            mirrorBitmap?.width != width ||
            mirrorBitmap?.height != height
        ) {
            mirrorBitmap?.recycle()
            mirrorBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        val bitmap = mirrorBitmap ?: return null

        // Ensure software rendering mode (set only once)
        if (source.layerType != LAYER_TYPE_SOFTWARE) {
            source.setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        return try {
            val canvas = Canvas(bitmap)
            // Clear previous frame residue
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            // WebView.draw(canvas) automatically accounts for scroll offset,
            // causing content to be drawn outside Bitmap bounds (resulting in black screen).
            // Reverse-translate the canvas to compensate, ensuring the visible area is drawn at (0,0).
            canvas.save()
            canvas.translate(-source.scrollX.toFloat(), -source.scrollY.toFloat())
            source.draw(canvas)
            canvas.restore()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    // Reuse Paint object to avoid per-frame creation
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    /**
     * Update TextureView display (synchronous drawing to prevent async post from overwriting Bitmap with next frame)
     */
    private fun updateTextureView(bitmap: Bitmap) {
        try {
            val canvas = lockCanvas(null) ?: return
            // Clear canvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

            // Calculate scale ratio to fit TextureView
            val scaleX = width.toFloat() / bitmap.width
            val scaleY = height.toFloat() / bitmap.height
            val scale = minOf(scaleX, scaleY)

            canvas.save()
            canvas.scale(scale, scale)

            // Center the display
            val dx = (width / scale - bitmap.width) / 2
            val dy = (height / scale - bitmap.height) / 2
            canvas.translate(dx, dy)

            canvas.drawBitmap(bitmap, 0f, 0f, drawPaint)

            canvas.restore()
            unlockCanvasAndPost(canvas)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update texture", e)
        }
    }

    /**
     * Update frame rate statistics
     */
    private fun updateFrameStats(frameStartTime: Long) {
        frameCount++
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastFrameTime

        if (elapsed >= 1000) {
            val fps = frameCount * 1000.0 / elapsed
            callback?.onFrameCaptured(fps.toFloat())
            Log.d(TAG, "Current FPS: ${String.format("%.2f", fps)}")

            frameCount = 0
            lastFrameTime = currentTime
        }
    }

    /**
     * Manually refresh a single frame
     */
    fun refreshFrame() {
        if (isMirroring && !isPaused) {
            captureFrame()
        }
    }

    /**
     * Adjust mirroring quality (dynamically adjust FPS)
     */
    fun adjustQualityForPerformance(isHighPerformance: Boolean) {
        if (isHighPerformance) {
            setTargetFps(15)  // Lower FPS to save power
        } else {
            setTargetFps(30)  // Normal FPS
        }
    }

    /**
     * Get the currently displayed Bitmap
     */
    fun getCurrentBitmap(): Bitmap? {
        return mirrorBitmap?.copy(mirrorBitmap?.config ?: Bitmap.Config.ARGB_8888, false)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMirroring()
    }
}