package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton

object HomeUiHelper {

    private data class ButtonConfig(
        val button: MaterialButton?,
        val defaultDrawableRes: Int,
        val customColor: Int,
        val isEnabled: Boolean
    )

    fun isEnabled(chosen: Boolean, isConnected: Boolean): Boolean = chosen || isConnected

    fun applyButtonVisibility(
        rootView: View,
        settings: Settings,
        isConnected: Boolean = false
    ) {
        val wirelessOnly = settings.wirelessAndroidOnly
        applyModeButton(
            rootView.findViewById(R.id.self_mode_button),
            rootView.findViewById(R.id.self_mode_text),
            visible = !wirelessOnly,
            enabled = settings.offersSelf(isConnected),
        )
        applyModeButton(
            rootView.findViewById(R.id.usb_button),
            rootView.findViewById(R.id.usb_text),
            visible = !wirelessOnly,
            enabled = settings.offersUsb(),
        )
        applyModeButton(
            rootView.findViewById(R.id.wifi_button),
            rootView.findViewById(R.id.wifi_text),
            visible = true,
            enabled = settings.showsWifi(),
        )

        placeLandscapeButtons(rootView, wirelessOnly)
    }

    private fun applyModeButton(button: View?, label: View?, visible: Boolean, enabled: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        button?.visibility = visibility
        button?.isEnabled = enabled
        button?.isClickable = enabled
        label?.visibility = visibility
        label?.alpha = if (enabled) 1.0f else 0.4f
    }

    /**
     * Landscape lays the four buttons in fixed quarters. With only Wi-Fi and Settings left,
     * those two move into the middle quarters so each keeps the four-button size.
     */
    private fun placeLandscapeButtons(rootView: View, wirelessOnly: Boolean) {
        val layout = rootView.findViewById<View>(R.id.main_buttons_layout) as? ConstraintLayout ?: return
        val set = ConstraintSet()
        set.clone(layout)
        val wifiStart = if (wirelessOnly) R.id.home_slot_25 else R.id.home_slot_50
        val wifiEnd = if (wirelessOnly) R.id.home_slot_50 else R.id.home_slot_75
        val settingsStart = if (wirelessOnly) R.id.home_slot_50 else R.id.home_slot_75
        val settingsEnd = if (wirelessOnly) R.id.home_slot_75 else ConstraintSet.PARENT_ID
        set.connect(R.id.wifi_button, ConstraintSet.START, wifiStart, ConstraintSet.START)
        set.connect(R.id.wifi_button, ConstraintSet.END, wifiEnd, ConstraintSet.START)
        set.connect(R.id.settings_button, ConstraintSet.START, settingsStart, ConstraintSet.START)
        set.connect(
            R.id.settings_button,
            ConstraintSet.END,
            settingsEnd,
            if (settingsEnd == ConstraintSet.PARENT_ID) ConstraintSet.END else ConstraintSet.START
        )
        set.applyTo(layout)
    }

    fun applyButtonScale(
        rootView: View,
        scalePercent: Int,
        isPortrait: Boolean,
        density: Float
    ) {
        val validScale = if (scalePercent in 60..120) scalePercent else 100
        val scaleFactor = validScale / 100.0f

        val selfBtn = rootView.findViewById<View>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<View>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<View>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<View>(R.id.settings_button)
        val buttons = listOfNotNull(selfBtn, usbBtn, wifiBtn, settingsBtn)

        if (isPortrait) {
            val basePaddingDp = 12f
            val adjustedPaddingPx = ((basePaddingDp * (2.0f - scaleFactor)).coerceIn(4f, 16f) * density).toInt()
            buttons.forEach { button ->
                (button.parent as? View)?.setPadding(adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx, adjustedPaddingPx)
            }
        } else {
            val baseMarginDp = 40f
            val adjustedMarginPx = ((baseMarginDp * (2.0f - scaleFactor)).coerceIn(12f, 48f) * density).toInt()
            buttons.forEach { button ->
                val params = button.layoutParams as? ViewGroup.MarginLayoutParams
                if (params != null) {
                    params.setMargins(adjustedMarginPx, adjustedMarginPx, adjustedMarginPx, adjustedMarginPx)
                    button.layoutParams = params
                }
            }
        }

        val mainButtonsLayout = rootView.findViewById<View>(R.id.main_buttons_layout)
        if (mainButtonsLayout != null) {
            mainButtonsLayout.scaleX = scaleFactor
            mainButtonsLayout.scaleY = scaleFactor
        }
    }

    fun applyButtonStyles(
        context: Context,
        rootView: View,
        settings: Settings,
        isNightActive: Boolean,
        isConnected: Boolean = false
    ) {
        val selfBtn = rootView.findViewById<MaterialButton>(R.id.self_mode_button)
        val usbBtn = rootView.findViewById<MaterialButton>(R.id.usb_button)
        val wifiBtn = rootView.findViewById<MaterialButton>(R.id.wifi_button)
        val settingsBtn = rootView.findViewById<MaterialButton>(R.id.settings_button)

        val isDarkTheme = settings.appTheme == Settings.AppTheme.DARK ||
                settings.appTheme == Settings.AppTheme.EXTREME_DARK ||
                isNightActive

        val showsSelf = isEnabled(settings.showsSelf(), isConnected)
        val showsUsb = settings.showsUsb()
        val showsWifi = settings.showsWifi()

        val disabledBackground = ContextCompat.getDrawable(context, R.drawable.gradient_monochrome)
        val disabledIconTint = ContextCompat.getColorStateList(context, R.color.disabled_icon_tint)

        val isMonochrome = isDarkTheme && settings.autoMonochromeButtonsAtNight
        val monochromeBackground = ContextCompat.getDrawable(context, R.drawable.gradient_monochrome)
        val monochromeIconTint = ContextCompat.getColorStateList(context, R.color.monochrome_icon_tint)
        val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())

        val buttonConfigs = listOf(
            ButtonConfig(selfBtn, R.drawable.gradient_blue, settings.customSelfModeButtonColor, showsSelf),
            ButtonConfig(usbBtn, R.drawable.gradient_orange, settings.customUsbButtonColor, showsUsb),
            ButtonConfig(wifiBtn, R.drawable.gradient_purple, settings.customWifiButtonColor, showsWifi),
            ButtonConfig(settingsBtn, R.drawable.gradient_darkblue, settings.customSettingsButtonColor, true)
        )

        buttonConfigs.forEach { (button, defaultDrawableRes, customColor, isEnabled) ->
            if (button != null) {
                if (!isEnabled) {
                    button.background = disabledBackground?.constantState?.newDrawable()?.mutate()
                    button.iconTint = disabledIconTint
                } else if (isMonochrome) {
                    button.background = monochromeBackground?.constantState?.newDrawable()?.mutate()
                    button.iconTint = monochromeIconTint
                } else {
                    if (customColor != 0) {
                        button.background = ColorUtils.createGradientDrawable(customColor, 32f, context)
                    } else {
                        button.background = ContextCompat.getDrawable(context, defaultDrawableRes)
                    }
                    button.iconTint = whiteTint
                }
            }
        }
    }
}
