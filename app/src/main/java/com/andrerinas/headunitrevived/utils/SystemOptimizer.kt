package com.andrerinas.headunitrevived.utils

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.sqrt

class SystemOptimizer(private val context: Context) {

    data class OptimizationResult(
        val recommendedResolutionId: Int,
        val recommendedDpi: Int,
        val recommendedVideoCodec: String,
        val isWidescreen: Boolean,
        val suggestedOrientation: Settings.ScreenOrientation,
        val h265Support: Boolean
    )

    enum class DisplaySizePreset(val diagonalInch: Float) {
        PHONE_4_6(6.0f),
        SMALL_7_8(7.5f),
        STANDARD_9_10(10.0f),
        LARGE_11_PLUS(12.5f)
    }

    fun checkH265HardwareSupport(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals("video/hevc", ignoreCase = true)) {
                    val name = info.name.lowercase()
                    val isSoftware = name.startsWith("omx.google.") || 
                                   name.startsWith("c2.android.") || 
                                   name.startsWith("omx.ffmpeg.")
                    if (!isSoftware) return true
                }
            }
        }
        return false
    }

    fun calculateOptimalSettings(
        sizePreset: DisplaySizePreset,
        isPortraitTarget: Boolean
    ): OptimizationResult {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.getRealMetrics(metrics)
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val densityDpi = metrics.densityDpi
        
        // Use orientation-independent diagonal for base calculation
        val pixelDiagonal = sqrt(width * width + height * height)
        val aspectRatio = if (width > height) width / height else height / width
        
        val hasH265 = checkH265HardwareSupport()
        
        // 1. Resolution Recommendation
        val recResId = when {
            width >= 2560 && hasH265 -> 4 
            width >= 1080 || densityDpi >= 320 || aspectRatio > 2.0f -> 3
            else -> 2
        }

        // 2. Calculated DPI Strategy
        // Formula: (PixelDiagonal / InchDiagonal) * ComfortFactor(1.2)
        val calculatedDpi = (pixelDiagonal / sizePreset.diagonalInch) * 1.2f
        var recDpi = calculatedDpi.toInt()

        // 3. Apply orientation-based caps to keep UI stable
        if (isPortraitTarget) {
            // Cap at 190 to prevent Google Maps rendering bugs in portrait
            recDpi = recDpi.coerceAtMost(190)
        } else {
            // Cap at 240 for landscape (User's choice for 10.25" widescreen)
            recDpi = recDpi.coerceAtMost(240)
        }

        // Global lower bound for usability
        recDpi = recDpi.coerceAtLeast(110)

        return OptimizationResult(
            recommendedResolutionId = recResId,
            recommendedDpi = recDpi,
            recommendedVideoCodec = if (hasH265) "H.265" else "H.264",
            isWidescreen = aspectRatio > 1.7f,
            suggestedOrientation = if (isPortraitTarget) Settings.ScreenOrientation.PORTRAIT else Settings.ScreenOrientation.LANDSCAPE,
            h265Support = hasH265
        )
    }
}
