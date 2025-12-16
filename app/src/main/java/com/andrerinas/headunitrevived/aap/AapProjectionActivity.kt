package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.TouchEvent
import com.andrerinas.headunitrevived.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.headunitrevived.app.SurfaceActivity
import com.andrerinas.headunitrevived.contract.KeyIntent
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.decoder.VideoDimensionsListener
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.IntentFilters
import com.andrerinas.headunitrevived.utils.ScreenSpec
import com.andrerinas.headunitrevived.utils.ScreenSpecProvider
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private lateinit var projectionView: IProjectionView
    private lateinit var screenSpec: ScreenSpec
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }
    private val touchMatrix = Matrix()


    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    private val keyCodeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val event: KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(KeyIntent.extraEvent, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(KeyIntent.extraEvent)
            }
            event?.let {
                onKeyEvent(it.keyCode, it.action == KeyEvent.ACTION_DOWN)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_headunit)

        videoDecoder.dimensionsListener = this

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid. All Rights Reserved...")

        val container = findViewById<android.widget.FrameLayout>(R.id.container)
        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(android.graphics.Color.BLACK)

            // Use the screen spec for the texture view for negotiation
            val displayMetrics = resources.displayMetrics
            screenSpec = ScreenSpecProvider.getSpecForTextureView(displayMetrics.widthPixels, displayMetrics.heightPixels, displayMetrics.densityDpi).screenSpec
        } else {
            AppLog.i("Using SurfaceView")
            screenSpec = ScreenSpecProvider.getSpec(this)
            projectionView = ProjectionView(this)
        }

        val view = projectionView as android.view.View
        container.addView(view)

        projectionView.addCallback(this)

        view.setOnTouchListener { _, event ->
            sendTouchEvent(event)
            true
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(disconnectReceiver)
        unregisterReceiver(keyCodeReceiver)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            registerReceiver(disconnectReceiver, IntentFilters.disconnect, RECEIVER_NOT_EXPORTED)
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, IntentFilters.disconnect)
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent)
        }
    }

    val transport: AapTransport
        get() = App.provide(this).transport

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        videoDecoder.onSurfaceAvailable(surface)
        transport.send(VideoFocusEvent(gain = true, unsolicited = false))

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                val textureProjectionView = (projectionView as? TextureProjectionView)
                textureProjectionView?.setVideoSize(currentVideoWidth, currentVideoHeight)
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        transport.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            val textureProjectionView = (projectionView as? TextureProjectionView)
            textureProjectionView?.setVideoSize(width, height)

            // Update the touch matrix for correct touch event transformation
            val view = projectionView as android.view.View
            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()

            if (viewWidth > 0 && viewHeight > 0 && width > 0 && height > 0) {
                // Retrieve the actual scaleX and scaleY applied to the TextureProjectionView
                val actualScaleX = (projectionView as TextureProjectionView).scaleX
                val actualScaleY = (projectionView as TextureProjectionView).scaleY

                val forwardMatrix = Matrix()
                // The view is scaled around its center, so we build the forward matrix
                // the same way.
                forwardMatrix.setScale(actualScaleX, actualScaleY, viewWidth / 2f, viewHeight / 2f)

                // The touch matrix is the inverse of the view's transformation matrix.
                if (!forwardMatrix.invert(touchMatrix)) {
                    AppLog.e("AapProjectionActivity", "Failed to invert the transformation matrix for touch events.")
                }

                AppLog.i("Touch matrix updated for ${width}x$height video on ${viewWidth}x$viewHeight view. Actual scales: ${actualScaleX}x${actualScaleY}")
            }
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        // Get the raw touch coordinates
        val rawX = event.getX(event.actionIndex)
        val rawY = event.getY(event.actionIndex)

        // Apply the inverse transformation for touch events
        val transformedPoints = floatArrayOf(rawX, rawY)
        touchMatrix.mapPoints(transformedPoints)

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val x = transformedPoints[0].toInt()
            val y = transformedPoints[1].toInt()

            // Boundary check against the negotiated screen size
            if (x < 0 || x >= screenSpec.width || y < 0 || y >= screenSpec.height) {
                AppLog.w("Touch event out of bounds of negotiated screen spec, skipping. x=$x, y=$y, spec=$screenSpec")
                return
            }
            pointerData.add(Triple(pointerId, x, y))
        }

        transport.send(TouchEvent(ts, action, event.actionIndex, pointerData))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("KeyCode: %d", keyCode)
        // PRes navigation on the screen
        onKeyEvent(keyCode, true)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        AppLog.i("onKeyUp: %d", keyCode)
        onKeyEvent(keyCode, false)
        return super.onKeyUp(keyCode, event)
    }


    private fun onKeyEvent(keyCode: Int, isPress: Boolean) {
        transport.send(keyCode, isPress)
    }

    override fun onDestroy() {
        super.onDestroy()
        videoDecoder.dimensionsListener = null
    }

    companion object {
        const val EXTRA_FOCUS = "focus"

        fun intent(context: Context): Intent {
            val aapIntent = Intent(context, AapProjectionActivity::class.java)
            aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return aapIntent
        }
    }
}
