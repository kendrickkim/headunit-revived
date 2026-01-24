package com.andrerinas.headunitrevived.aap

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
import com.andrerinas.headunitrevived.view.IProjectionView
import com.andrerinas.headunitrevived.view.ProjectionView
import com.andrerinas.headunitrevived.view.TextureProjectionView
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.view.OverlayTouchView
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig
import com.andrerinas.headunitrevived.utils.SystemUI

class AapProjectionActivity : SurfaceActivity(), IProjectionView.Callbacks, VideoDimensionsListener {

    private lateinit var projectionView: IProjectionView
    private val videoDecoder: VideoDecoder by lazy { App.provide(this).videoDecoder }
    private val settings: Settings by lazy { Settings(this) }

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            AppLog.i("AapProjectionActivity received disconnect signal, finishing.")
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

        val screenOrientation = settings.screenOrientation
        if (screenOrientation == Settings.ScreenOrientation.AUTO) {
            // AUTO mode: lock to current orientation at launch (existing behavior)
            if (Build.VERSION.SDK_INT >= 18) {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
            } else {
                requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR
            }
        } else {
            requestedOrientation = screenOrientation.androidOrientation
        }

        setContentView(R.layout.activity_headunit)

        // Register disconnect receiver here to stay active even if activity is paused
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(disconnectReceiver, IntentFilters.disconnect, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, IntentFilters.disconnect)
        }

        videoDecoder.dimensionsListener = this

        AppLog.i("HeadUnit for Android Auto (tm) - Copyright 2011-2015 Michael A. Reid., since 2025 André Rinas All Rights Reserved...")

        val container = findViewById<android.widget.FrameLayout>(R.id.container)
        val displayMetrics = resources.displayMetrics

        if (settings.viewMode == Settings.ViewMode.TEXTURE) {
            AppLog.i("Using TextureView")
            val textureView = TextureProjectionView(this)
            textureView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = textureView
            container.setBackgroundColor(android.graphics.Color.BLACK)
        } else if (settings.viewMode == Settings.ViewMode.GLES) {
            AppLog.i("Using GlProjectionView")
            val glView = com.andrerinas.headunitrevived.view.GlProjectionView(this)
            glView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            projectionView = glView
            container.setBackgroundColor(android.graphics.Color.BLACK)
        } else {
            AppLog.i("Using SurfaceView")
            projectionView = ProjectionView(this)
            (projectionView as android.view.View).layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        // Use the same screen conf for both views for negotiation
        HeadUnitScreenConfig.init(this, displayMetrics, settings)

        val view = projectionView as android.view.View
        container.addView(view)

        projectionView.addCallback(this)

        val overlayView = OverlayTouchView(this)
        overlayView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        overlayView.setOnTouchListener { _, event ->
                sendTouchEvent(event)
                true
            }

        container.addView(overlayView)
        setFullscreen() // Call setFullscreen here as well
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(keyCodeReceiver)
        // Disconnect receiver is unregistered in onDestroy
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(keyCodeReceiver, IntentFilters.keyEvent)
        }
        setFullscreen() // Call setFullscreen here as well
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            setFullscreen() // Reapply fullscreen mode if window gains focus
        }
    }

    private fun setFullscreen() {
        val container = findViewById<View>(R.id.container)
        SystemUI.apply(window, container, settings.startInFullscreenMode)
    }

    val transport: AapTransport
        get() = App.provide(this).transport

    override fun onSurfaceCreated(surface: android.view.Surface) {
        AppLog.i("[AapProjectionActivity] onSurfaceCreated")
        // Decoder configuration is now in onSurfaceChanged
    }

    override fun onSurfaceChanged(surface: android.view.Surface, width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] onSurfaceChanged. Actual surface dimensions: width=$width, height=$height")
        
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            AppLog.i("Delayed setting surface to decoder")
            videoDecoder.setSurface(surface)

            // Simply request focus to ensure stream is active
            transport.send(VideoFocusEvent(gain = true, unsolicited = false))
        }, 750)

        // Explicitly check and set video dimensions if already known by the decoder
        // This handles cases where the activity is recreated but the decoder already has dimensions
        val currentVideoWidth = videoDecoder.videoWidth
        val currentVideoHeight = videoDecoder.videoHeight

        if (currentVideoWidth > 0 && currentVideoHeight > 0) {
            AppLog.i("[AapProjectionActivity] Decoder already has dimensions: ${currentVideoWidth}x$currentVideoHeight. Applying to view.")
            runOnUiThread {
                projectionView.setVideoSize(currentVideoWidth, currentVideoHeight)
                projectionView.setVideoScale(HeadUnitScreenConfig.getScaleX(), HeadUnitScreenConfig.getScaleY())
            }
        }
    }

    override fun onSurfaceDestroyed(surface: android.view.Surface) {
        AppLog.i("SurfaceCallback: onSurfaceDestroyed. Surface: $surface")
        transport.send(VideoFocusEvent(gain = false, unsolicited = false))
        videoDecoder.stop("surfaceDestroyed")
    }

    override fun onVideoDimensionsChanged(width: Int, height: Int) {
        AppLog.i("[AapProjectionActivity] Received video dimensions: ${width}x$height")
        runOnUiThread {
            projectionView.setVideoSize(width, height)
            projectionView.setVideoScale(HeadUnitScreenConfig.getScaleX(), HeadUnitScreenConfig.getScaleY())
        }
    }

    private fun sendTouchEvent(event: MotionEvent) {
        val action = TouchEvent.motionEventToAction(event) ?: return
        val ts = SystemClock.elapsedRealtime()

        val horizontalCorrection = HeadUnitScreenConfig.getHorizontalCorrection()
        val verticalCorrection = HeadUnitScreenConfig.getVerticalCorrection()

        val pointerData = mutableListOf<Triple<Int, Int, Int>>()
        repeat(event.pointerCount) { pointerIndex ->
            val pointerId = event.getPointerId(pointerIndex)
            val x = event.getX(pointerIndex)
            val y = event.getY(pointerIndex)

            val correctedX = (x * horizontalCorrection).toInt()
            val correctedY = (y * verticalCorrection).toInt()

            pointerData.add(Triple(pointerId, correctedX, correctedY))
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
        AppLog.i("AapProjectionActivity.onDestroy called. isFinishing=$isFinishing")
        unregisterReceiver(disconnectReceiver)
        videoDecoder.dimensionsListener = null

        if (isFinishing && AapService.isConnected) {
            transport.stop()
        }
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