package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings
import com.andrerinas.headunitrevived.BuildConfig
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    // Local state to hold changes before saving
    private var pendingNightMode: Settings.NightMode? = null
    private var pendingMicSampleRate: Int? = null
    private var pendingUseGps: Boolean? = null
    private var pendingResolution: Int? = null
    private var pendingDpi: Int? = null
    private var pendingFullscreen: Boolean? = null
    private var pendingViewMode: Settings.ViewMode? = null
    private var pendingForceSoftware: Boolean? = null
    private var pendingRightHandDrive: Boolean? = null
    private var pendingWifiLauncherMode: Boolean? = null
    private var pendingAutoConnectLastSession: Boolean? = null
    private var pendingVideoCodec: String? = null
    private var pendingFpsLimit: Int? = null
    private var pendingDebugMode: Boolean? = null
    private var pendingBluetoothAddress: String? = null
    private var pendingEnableAudioSink: Boolean? = null
    private var pendingUseAacAudio: Boolean? = null
    private var pendingUseNativeSsl: Boolean? = null
    private var pendingAutoStartSelfMode: Boolean? = null
    private var pendingScreenOrientation: Settings.ScreenOrientation? = null

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize local state with current values
        pendingNightMode = settings.nightMode
        pendingMicSampleRate = settings.micSampleRate
        pendingUseGps = settings.useGpsForNavigation
        pendingResolution = settings.resolutionId
        pendingDpi = settings.dpiPixelDensity
        pendingFullscreen = settings.startInFullscreenMode
        pendingViewMode = settings.viewMode
        pendingForceSoftware = settings.forceSoftwareDecoding
        pendingRightHandDrive = settings.rightHandDrive
        pendingWifiLauncherMode = settings.wifiLauncherMode
        pendingAutoConnectLastSession = settings.autoConnectLastSession
        pendingVideoCodec = settings.videoCodec
        pendingFpsLimit = settings.fpsLimit
        pendingDebugMode = settings.debugMode
        pendingBluetoothAddress = settings.bluetoothAddress
        pendingEnableAudioSink = settings.enableAudioSink
        pendingUseAacAudio = settings.useAacAudio
        pendingUseNativeSsl = settings.useNativeSsl
        pendingAutoStartSelfMode = settings.autoStartSelfMode
        pendingScreenOrientation = settings.screenOrientation

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }
        
        // Add the Save item with custom layout
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        // Get the button from the action view
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            AlertDialog.Builder(requireContext())
                .setTitle("Unsaved Changes")
                .setMessage("You have unsaved changes. Do you want to discard them?")
                .setPositiveButton("Discard") { _, _ ->
                    findNavController().popBackStack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            findNavController().popBackStack()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        pendingNightMode?.let { settings.nightMode = it }
        pendingMicSampleRate?.let { settings.micSampleRate = it }
        pendingUseGps?.let { settings.useGpsForNavigation = it }
        pendingResolution?.let { settings.resolutionId = it }
        pendingDpi?.let { settings.dpiPixelDensity = it }
        pendingFullscreen?.let { settings.startInFullscreenMode = it }
        pendingViewMode?.let { settings.viewMode = it }
        pendingForceSoftware?.let { settings.forceSoftwareDecoding = it }
        pendingRightHandDrive?.let { settings.rightHandDrive = it }
        pendingVideoCodec?.let { settings.videoCodec = it }
        pendingFpsLimit?.let { settings.fpsLimit = it }
        pendingDebugMode?.let { settings.debugMode = it }
        pendingBluetoothAddress?.let { settings.bluetoothAddress = it }
        pendingEnableAudioSink?.let { settings.enableAudioSink = it }
        pendingUseAacAudio?.let { settings.useAacAudio = it }
        pendingUseNativeSsl?.let { settings.useNativeSsl = it }
        pendingAutoStartSelfMode?.let { settings.autoStartSelfMode = it }
        pendingScreenOrientation?.let { settings.screenOrientation = it }

        pendingWifiLauncherMode?.let { enabled ->
            settings.wifiLauncherMode = enabled
            val intent = Intent(requireContext(), AapService::class.java).apply {
                action = if (enabled) AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            requireContext().startService(intent)
        }

        pendingAutoConnectLastSession?.let { settings.autoConnectLastSession = it }

        if (requiresRestart) {
            if (AapService.isConnected) {
                Toast.makeText(context, "Stopping service to apply changes...", Toast.LENGTH_SHORT).show()
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                requireContext().startService(stopServiceIntent)
            }
        }
        
        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()
        
        Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
    }

    private fun checkChanges() {
        // Check for any changes
        val anyChange = pendingNightMode != settings.nightMode ||
                        pendingMicSampleRate != settings.micSampleRate ||
                        pendingUseGps != settings.useGpsForNavigation ||
                        pendingResolution != settings.resolutionId ||
                        pendingDpi != settings.dpiPixelDensity ||
                        pendingFullscreen != settings.startInFullscreenMode ||
                        pendingViewMode != settings.viewMode ||
                        pendingForceSoftware != settings.forceSoftwareDecoding ||
                        pendingRightHandDrive != settings.rightHandDrive ||
                        pendingWifiLauncherMode != settings.wifiLauncherMode ||
                        pendingAutoConnectLastSession != settings.autoConnectLastSession ||
                        pendingVideoCodec != settings.videoCodec ||
                        pendingFpsLimit != settings.fpsLimit ||
                        pendingDebugMode != settings.debugMode ||
                        pendingBluetoothAddress != settings.bluetoothAddress ||
                        pendingEnableAudioSink != settings.enableAudioSink ||
                        pendingUseAacAudio != settings.useAacAudio ||
                        pendingUseNativeSsl != settings.useNativeSsl ||
                        pendingAutoStartSelfMode != settings.autoStartSelfMode ||
                        pendingScreenOrientation != settings.screenOrientation

        hasChanges = anyChange

        // Check for restart requirement
        requiresRestart = pendingResolution != settings.resolutionId ||
                          pendingVideoCodec != settings.videoCodec ||
                          pendingFpsLimit != settings.fpsLimit ||
                          pendingDpi != settings.dpiPixelDensity ||
                          pendingForceSoftware != settings.forceSoftwareDecoding ||
                          pendingRightHandDrive != settings.rightHandDrive ||
                          pendingEnableAudioSink != settings.enableAudioSink ||
                          pendingUseAacAudio != settings.useAacAudio ||
                          pendingUseNativeSsl != settings.useNativeSsl

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        // --- General Settings ---
        items.add(SettingItem.CategoryHeader("general", R.string.category_general))
        
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = resources.getStringArray(R.array.night_mode)[pendingNightMode!!.value],
            onClick = { _ ->
                val nightModeTitles = resources.getStringArray(R.array.night_mode)
                
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(nightModeTitles, pendingNightMode!!.value) { dialog, which ->
                        pendingNightMode = Settings.NightMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "micSampleRate",
            nameResId = R.string.mic_sample_rate,
            value = "${pendingMicSampleRate!! / 1000}kHz",
            onClick = { _ ->
                val currentSampleRateIndex = Settings.MicSampleRates.indexOf(pendingMicSampleRate!!)
                val sampleRateNames = Settings.MicSampleRates.map { "${it / 1000}kHz" }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mic_sample_rate)
                    .setSingleChoiceItems(sampleRateNames, currentSampleRateIndex) { dialog, which ->
                        val newValue = Settings.MicSampleRates.elementAt(which)
                        pendingMicSampleRate = newValue
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description),
            onClick = { _ ->
                findNavController().navigate(R.id.action_settingsFragment_to_keymapFragment)
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "gpsNavigation",
            nameResId = R.string.gps_for_navigation,
            descriptionResId = R.string.gps_for_navigation_description,
            isChecked = pendingUseGps!!,
            onCheckedChanged = { isChecked ->
                pendingUseGps = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "rightHandDrive",
            nameResId = R.string.right_hand_drive,
            descriptionResId = R.string.right_hand_drive_description,
            isChecked = pendingRightHandDrive!!,
            onCheckedChanged = { isChecked ->
                pendingRightHandDrive = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "wifiLauncherMode",
            nameResId = R.string.wifi_launcher_mode,
            descriptionResId = R.string.wifi_launcher_mode_description,
            isChecked = pendingWifiLauncherMode!!,
            onCheckedChanged = { isChecked ->
                pendingWifiLauncherMode = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartSelfMode",
            nameResId = R.string.auto_start_self_mode,
            descriptionResId = R.string.auto_start_self_mode_description,
            isChecked = pendingAutoStartSelfMode!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartSelfMode = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoConnectLastSession",
            nameResId = R.string.auto_connect_last_session,
            descriptionResId = R.string.auto_connect_last_session_description,
            isChecked = pendingAutoConnectLastSession!!,
            onCheckedChanged = { isChecked ->
                pendingAutoConnectLastSession = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))
        
        items.add(SettingItem.SettingEntry(
            stableId = "resolution",
            nameResId = R.string.resolution,
            value = Settings.Resolution.fromId(pendingResolution!!)?.resName ?: "",
            onClick = { _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_resolution)
                    .setSingleChoiceItems(Settings.Resolution.allRes, pendingResolution!!) { dialog, which ->
                        pendingResolution = which
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "dpiPixelDensity",
            nameResId = R.string.dpi,
            value = if (pendingDpi == 0) getString(R.string.auto) else pendingDpi.toString(),
            onClick = { _ ->
                val editView = EditText(requireContext())
                editView.inputType = InputType.TYPE_CLASS_NUMBER
                if (pendingDpi != 0) {
                    editView.setText(pendingDpi.toString())
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.enter_dpi_value)
                    .setView(editView)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        val inputText = editView.text.toString().trim()
                        val newDpi = inputText.toIntOrNull()
                        if (newDpi != null && newDpi >= 0) {
                            pendingDpi = newDpi
                        } else {
                            pendingDpi = 0
                        }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                                    .setNegativeButton(R.string.cancel) { dialog, _ ->
                                        dialog.cancel()
                                    }                    .show()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            descriptionResId = R.string.start_in_fullscreen_mode_description,
            isChecked = pendingFullscreen!!,
            onCheckedChanged = { isChecked ->
                pendingFullscreen = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = when (pendingViewMode) {
                Settings.ViewMode.SURFACE -> getString(R.string.surface_view)
                Settings.ViewMode.TEXTURE -> getString(R.string.texture_view)
                Settings.ViewMode.GLES -> getString(R.string.gles_view)
                else -> getString(R.string.surface_view)
            },
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view), getString(R.string.gles_view))
                val currentIdx = pendingViewMode!!.value
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                        pendingViewMode = Settings.ViewMode.fromInt(which)!!
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = resources.getStringArray(R.array.screen_orientation)[pendingScreenOrientation!!.value],
            onClick = { _ ->
                val orientationOptions = resources.getStringArray(R.array.screen_orientation)
                val currentIdx = pendingScreenOrientation!!.value
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_screen_orientation)
                    .setSingleChoiceItems(orientationOptions, currentIdx) { dialog, which ->
                        pendingScreenOrientation = Settings.ScreenOrientation.fromInt(which)
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Video Settings ---
        items.add(SettingItem.CategoryHeader("video", R.string.category_video))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "forceSoftwareDecoding",
            nameResId = R.string.force_software_decoding,
            descriptionResId = R.string.force_software_decoding_description,
            isChecked = pendingForceSoftware!!,
            onCheckedChanged = { isChecked ->
                pendingForceSoftware = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = R.string.video_codec,
            value = pendingVideoCodec!!,
            onClick = { _ ->
                val codecs = arrayOf("Auto", "H.264", "H.265")
                val currentCodecIndex = codecs.indexOf(pendingVideoCodec)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.video_codec)
                    .setSingleChoiceItems(codecs, currentCodecIndex) { dialog, which ->
                        pendingVideoCodec = codecs[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "fpsLimit",
            nameResId = R.string.fps_limit,
            value = "${pendingFpsLimit} FPS",
            onClick = { _ ->
                val fpsOptions = arrayOf("30", "60")
                val currentFpsIndex = fpsOptions.indexOf(pendingFpsLimit.toString())
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.fps_limit)
                    .setSingleChoiceItems(fpsOptions, currentFpsIndex) { dialog, which ->
                        pendingFpsLimit = fpsOptions[which].toInt()
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // --- Audio Settings ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableAudioSink",
            nameResId = R.string.enable_audio_sink,
            descriptionResId = R.string.enable_audio_sink_description,
            isChecked = pendingEnableAudioSink!!,
            onCheckedChanged = { isChecked ->
                pendingEnableAudioSink = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAacAudio",
            nameResId = R.string.use_aac_audio,
            descriptionResId = R.string.use_aac_audio_description,
            isChecked = pendingUseAacAudio!!,
            onCheckedChanged = { isChecked ->
                pendingUseAacAudio = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "debugMode",
            nameResId = R.string.debug_mode,
            descriptionResId = R.string.debug_mode_description,
            isChecked = pendingDebugMode!!,
            onCheckedChanged = { isChecked ->
                pendingDebugMode = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = {
                val context = requireContext()
                val logFile = com.andrerinas.headunitrevived.utils.LogExporter.saveLogToPublicFile(context)

                if (logFile != null) {
                    MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                        .setTitle("Logs Exported")
                        .setMessage("Log saved to:\n${logFile.absolutePath}\n\n")
                        .setPositiveButton("Share") { _, _ ->
                            com.andrerinas.headunitrevived.utils.LogExporter.shareLogFile(context, logFile)
                        }
                        .setNegativeButton("Close") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(context, "Failed to export logs", Toast.LENGTH_SHORT).show()
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useNativeSsl",
            nameResId = R.string.use_native_ssl,
            descriptionResId = R.string.use_native_ssl_description,
            isChecked = pendingUseNativeSsl!!,
            onCheckedChanged = { isChecked ->
                pendingUseNativeSsl = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "bluetoothAddress",
            nameResId = R.string.bluetooth_address_s,
            value = pendingBluetoothAddress!!.ifEmpty { getString(R.string.not_set) },
            onClick = { _ ->
                val editView = EditText(requireContext())
                editView.setText(pendingBluetoothAddress)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.enter_bluetooth_mac)
                    .setView(editView)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        pendingBluetoothAddress = editView.text.toString().trim()
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                                    .setNegativeButton(R.string.cancel) { dialog, _ ->
                                        dialog.cancel()
                                    }                    .show()
            }
        ))

        // --- Info Settings ---
        items.add(SettingItem.CategoryHeader("info", R.string.category_info))

        items.add(SettingItem.SettingEntry(
            stableId = "version",
            nameResId = R.string.version,
            value = BuildConfig.VERSION_NAME,
            onClick = { /* Read only */ }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "about",
            nameResId = R.string.about,
            value = getString(R.string.about_description),
            onClick = {
                findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
            }
        ))

        settingsAdapter.submitList(items)
    }
}
