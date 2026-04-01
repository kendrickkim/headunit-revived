package com.andrerinas.headunitrevived.view

import android.view.View
import android.view.ViewGroup
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.HeadUnitScreenConfig

object ProjectionViewScaler {

    fun updateScale(view: View, videoWidth: Int, videoHeight: Int) {
        if (videoWidth == 0 || videoHeight == 0 || view.width == 0 || view.height == 0) {
            return
        }

        // Initialize/Update config
        HeadUnitScreenConfig.init(view.context, view.resources.displayMetrics, App.provide(view.context).settings)

        if (HeadUnitScreenConfig.forcedScale && view is ProjectionView) {
            // HUR Legacy Fix: Physically resize the view to the adjusted resolution
            // calculated by HeadUnitScreenConfig (preserving aspect ratio).
            val lp = view.layoutParams
            val targetW = HeadUnitScreenConfig.getAdjustedWidth()
            val targetH = HeadUnitScreenConfig.getAdjustedHeight()
            
            if (lp.width != targetW || lp.height != targetH) {
                lp.width = targetW
                lp.height = targetH
                view.layoutParams = lp
            }
            
            // In this mode, we don't use scaleX/Y (hardware props)
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            
            // Align center using margins from config
            // HeadUnitScreenConfig.getWidthMargin() returns the total black bar space.
            // We shift by half of that to center the view.
            view.translationX = -(HeadUnitScreenConfig.getWidthMargin().toFloat() / 2.0f)
            view.translationY = -(HeadUnitScreenConfig.getHeightMargin().toFloat() / 2.0f)
            
            AppLog.i("FORCED. Resized view to ${targetW}x${targetH}. Centering via Trans: ${view.translationX}x${view.translationY}")
        } else {
            // Modern way / TextureView: Use View scaling properties on a full-screen view
            val finalScaleX = HeadUnitScreenConfig.getScaleX()
            val finalScaleY = HeadUnitScreenConfig.getScaleY()

            // Reset layout params to match parent if they were changed
            if (view.layoutParams.width != ViewGroup.LayoutParams.MATCH_PARENT || 
                view.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
                val lp = view.layoutParams
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                view.layoutParams = lp
            }

            // Ensure pivot is centered (Android default)
            view.pivotX = view.width / 2.0f
            view.pivotY = view.height / 2.0f
            
            // Reset translation
            view.translationX = 0f
            view.translationY = 0f

            if (view is IProjectionView) {
                view.setVideoScale(finalScaleX, finalScaleY)
            } else {
                view.scaleX = finalScaleX
                view.scaleY = finalScaleY
            }
            AppLog.i("Normal Scale. scaleX: $finalScaleX, scaleY: $finalScaleY")
        }
    }
}
