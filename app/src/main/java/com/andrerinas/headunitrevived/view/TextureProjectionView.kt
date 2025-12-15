package com.andrerinas.headunitrevived.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import com.andrerinas.headunitrevived.utils.AppLog

class TextureProjectionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : TextureView(context, attrs, defStyleAttr), IProjectionView, TextureView.SurfaceTextureListener {

    private val callbacks = mutableListOf<IProjectionView.Callbacks>()
    private var surface: Surface? = null

    private var videoWidth = 0
    private var videoHeight = 0

    init {
        surfaceTextureListener = this
    }

    // ----------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        AppLog.i("TextureProjectionView: Video size set to ${width}x$height")
        videoWidth = width
        videoHeight = height
        updateScale()
    }

    // ----------------------------------------------------------------
    // Lifecycle & SurfaceTextureListener
    // ----------------------------------------------------------------

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface available: ${width}x$height")
        surface = Surface(surfaceTexture)
        surface?.let {
            // The width and height of the view are passed here, but the decoder should
            // use the actual video dimensions it parses from the SPS.
            callbacks.forEach { cb -> cb.onSurfaceChanged(it, width, height) }
        }
        // Set the default buffer size of the SurfaceTexture to the video dimensions
        // This ensures the underlying buffer matches the video content
        if (videoWidth > 0 && videoHeight > 0) {
            surfaceTexture.setDefaultBufferSize(videoWidth, videoHeight)
        }
        updateScale()
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        AppLog.i("TextureProjectionView: Surface size changed: ${width}x$height")
        updateScale()
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        AppLog.i("TextureProjectionView: Surface destroyed")
        surface?.let {
            callbacks.forEach { cb -> cb.onSurfaceDestroyed(it) }
        }
        surface?.release()
        surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // Not used
    }

    // ----------------------------------------------------------------
    // Core Transformation Logic
    // ----------------------------------------------------------------

    private fun updateScale() {
        if (videoWidth == 0 || videoHeight == 0 || width == 0 || height == 0) {
            return
        }

        // The dimensions of the content area we want to display
        val displayMetrics = resources.displayMetrics
        val contentWidth = displayMetrics.widthPixels
        val contentHeight = displayMetrics.heightPixels

        val sourceVideoWidth = videoWidth.toFloat()
        val sourceVideoHeight = videoHeight.toFloat()

        // This is the magic.
        // We scale the TextureView itself. Because the default pivot point is the
        // center, this effectively zooms into the center of the video stream.
        // The scale factor is the ratio of the full video size to the desired cropped content size.
        val finalScaleX = (sourceVideoWidth / contentWidth) * 1.0f
        val finalScaleY = (sourceVideoHeight / contentHeight) * 1.0f

        this.scaleX = finalScaleX
        this.scaleY = finalScaleY
        AppLog.i("TextureProjectionView: Dimensions: Video: ${videoWidth}x$videoHeight, Content: ${contentWidth}x$contentHeight")
        AppLog.i("TextureProjectionView: Scale updated. scaleX: $finalScaleX, scaleY: $finalScaleY")
    }


    // ----------------------------------------------------------------
    // Callbacks
    // ----------------------------------------------------------------

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }
}
