package com.andrerinas.headunitrevived.utils

import android.util.DisplayMetrics
import com.andrerinas.headunitrevived.aap.protocol.proto.Control
import kotlin.math.roundToInt

object HeadUnitScreenConfig {

    private var screenWidthPx: Int = 0
    private var screenHeightPx: Int = 0
    private var density: Float = 1.0f
    private var densityDpi: Int = 240
    private var scaleFactor: Float = 1.0f
    private var isSmallScreen: Boolean = true
    private var isPortraitScaled: Boolean = false
    private var isInitialized: Boolean = false // New flag
    var negotiatedResolutionType: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType? = null
    private lateinit var currentSettings: Settings // Store settings instance

    fun init(displayMetrics: DisplayMetrics, settings: Settings) {
        if (isInitialized) {
            //AppLog.i("HeadUnitScreenConfig already initialized. Skipping subsequent calls.")
            return
        }
        isInitialized = true
        currentSettings = settings // Store the settings instance

        val selectedResolution = Settings.Resolution.fromId(settings.resolutionId)

        screenWidthPx = displayMetrics.widthPixels
        screenHeightPx = displayMetrics.heightPixels
        density = displayMetrics.density
        densityDpi = displayMetrics.densityDpi

        if (screenWidthPx == 0 || screenHeightPx == 0) {
            return
        }
        //AppLog.i("CarScreen: width: $screenWidthPx height: $screenHeightPx")

        // check if small screen
        if (screenHeightPx > screenWidthPx) { // Portrait mode
            if (screenWidthPx > 1080 || screenHeightPx > 1920) {
                isSmallScreen = false
            }
        } else {
            if (screenWidthPx > 1920 || screenHeightPx > 1080) {
                isSmallScreen = false
            }
        }

        // Determine negotiatedResolutionType based on physical pixels if AUTO was selected
        if (selectedResolution == Settings.Resolution.AUTO) {
            if (screenHeightPx > screenWidthPx) { // Portrait mode
                if (screenWidthPx > 720 || screenHeightPx > 1280) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1080x1920
                } else {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._720x1280
                }
            } else { // Landscape mode
                if (screenWidthPx <= 800 && screenHeightPx <= 480) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480
                } else if (screenWidthPx > 1280 || screenHeightPx > 720) {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
                } else {
                    negotiatedResolutionType = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
                }
            }
        } else {
            negotiatedResolutionType = selectedResolution?.codec
        }

        if (!isSmallScreen) {
            val sWidth = screenWidthPx.toFloat()
            val sHeight = screenHeightPx.toFloat()
            if (sWidth / sHeight < getAspectRatio()) {
                isPortraitScaled = true
                scaleFactor = (sHeight * 1.0f) / getNegotiatedHeight().toFloat()
            } else {
                isPortraitScaled = false
                scaleFactor = (sWidth * 1.0f) / getNegotiatedWidth().toFloat()
            }
        }
        //AppLog.i("CarScreen isSmallScreen: $isSmallScreen, scaleFactor: ${scaleFactor}")
        //AppLog.i("CarScreen using: $negotiatedResolutionType, number: ${negotiatedResolutionType?.number}, scales: scaleX: ${getScaleX()}, scaleY: ${getScaleY()}")
    }

    fun getAdjustedHeight(): Int {
        return (getNegotiatedHeight() * scaleFactor).roundToInt()
    }

    fun getAdjustedWidth(): Int {
        return (getNegotiatedWidth() * scaleFactor).roundToInt()
    }

    private fun getAspectRatio(): Float {
        return getNegotiatedWidth().toFloat() / getNegotiatedHeight().toFloat()
    }

    fun getNegotiatedHeight(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return resString.split("x")[1].toInt()
    }

    fun getNegotiatedWidth(): Int {
        val resString = negotiatedResolutionType.toString().replace("_", "")
        return resString.split("x")[0].toInt()
    }

    fun getHeightMargin(): Int {
        //AppLog.i("CarScreen: Zoom is: $scaleFactor, adjusted height: ${getAdjustedHeight()}")
        val margin = ((getAdjustedHeight() - screenHeightPx) / scaleFactor).roundToInt()
        return margin.coerceAtLeast(0)
    }

    fun getWidthMargin(): Int {
        val margin = ((getAdjustedWidth() - screenWidthPx) / scaleFactor).roundToInt()
        //AppLog.i("CarScreen: Zoom is: $scaleFactor, adjusted width: ${getAdjustedWidth()}")
        return margin.coerceAtLeast(0)
    }

    private fun divideOrOne(numerator: Float, denominator: Float): Float {
        return if (denominator == 0.0f) 1.0f else numerator / denominator
    }

    fun getScaleX(): Float {
        //AppLog.i("GetScaleX: getNegotiatedWidth: ${getNegotiatedWidth()}, screenWidthPx: $screenWidthPx")
        if (getNegotiatedWidth() > screenWidthPx) {
            return divideOrOne(getNegotiatedWidth().toFloat(), screenWidthPx.toFloat())
        }
        if (isPortraitScaled) {
            return divideOrOne(getAspectRatio(), (screenWidthPx.toFloat() / screenHeightPx.toFloat()))
        }
        return 1.0f
    }

    fun getScaleY(): Float {
        if (getNegotiatedHeight() > screenHeightPx) {
            return divideOrOne(getNegotiatedHeight().toFloat(), screenHeightPx.toFloat())
        }
        if (isPortraitScaled) {
            return 1.0f
        }
        return divideOrOne((screenWidthPx.toFloat() / screenHeightPx.toFloat()), getAspectRatio())
    }

    fun getDensityWidth(): Int {
        return (screenWidthPx / density).roundToInt()
    }

    fun getDensityHeight(): Int {
        return (screenHeightPx / density).roundToInt()
    }

    fun getDensityDpi(): Int {
        return if (this::currentSettings.isInitialized && currentSettings.dpiPixelDensity != 0) {
            currentSettings.dpiPixelDensity
        } else {
            densityDpi
        }
    }

    fun getHorizontalCorrection(): Float {
        AppLog.i("CarScreen: Horizontal correction: 0, width ${getNegotiatedWidth()}, marg: ${getWidthMargin()}, width: $screenWidthPx")
        return (getNegotiatedWidth() - getWidthMargin()).toFloat() / screenWidthPx.toFloat()
    }

    fun getVerticalCorrection(): Float {
        val fIntValue = (getNegotiatedHeight() - getHeightMargin()).toFloat() / screenHeightPx.toFloat()
        AppLog.i("CarScreen: Vertical correction: $fIntValue, height ${getNegotiatedHeight()}, marg: ${getHeightMargin()}, height: $screenHeightPx")
        return fIntValue
    }
}
