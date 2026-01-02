package com.andrerinas.headunitrevived.main

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.decoder.MicRecorder
import com.andrerinas.headunitrevived.main.settings.SettingItem
import com.andrerinas.headunitrevived.main.settings.SettingsAdapter
import com.andrerinas.headunitrevived.utils.Settings

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize adapter once and set it to the RecyclerView
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        updateSettingsList()
    }

    private fun updateSettingsList() {
        val items = mutableListOf<SettingItem>()

        // --- General Settings ---
        items.add(SettingItem.CategoryHeader("general", R.string.category_general))
        
        items.add(SettingItem.SettingEntry(
            stableId = "nightMode",
            nameResId = R.string.night_mode,
            value = resources.getStringArray(R.array.night_mode)[settings.nightMode.value],
            onClick = { _ ->
                val nightModeTitles = resources.getStringArray(R.array.night_mode)
                val currentNightModeIndex = settings.nightMode.value
                
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.night_mode)
                    .setSingleChoiceItems(nightModeTitles, currentNightModeIndex) { dialog, which ->
                        val newMode = Settings.NightMode.fromInt(which)!!
                        settings.nightMode = newMode
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "micSampleRate",
            nameResId = R.string.mic_sample_rate,
            value = "${settings.micSampleRate / 1000}kHz",
            onClick = { _ ->
                val currentSampleRateIndex = Settings.MicSampleRates.indexOf(settings.micSampleRate)
                val sampleRateNames = Settings.MicSampleRates.map { "${it / 1000}kHz" }.toTypedArray()

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.mic_sample_rate)
                    .setSingleChoiceItems(sampleRateNames, currentSampleRateIndex) { dialog, which ->
                        val newValue = Settings.MicSampleRates.elementAt(which)

                        val recorder: MicRecorder? = try { MicRecorder(newValue, requireContext().applicationContext) } catch (e: Exception) { null }

                        if (recorder == null) {
                            Toast.makeText(activity, "Value not supported: $newValue", Toast.LENGTH_LONG).show()
                        } else {
                            settings.micSampleRate = newValue
                        }
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description), // Use new string resource
            onClick = { _ ->
                parentFragmentManager.beginTransaction()
                    .replace(R.id.main_content, KeymapFragment())
                    .addToBackStack(null)
                    .commit()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "gpsNavigation",
            nameResId = R.string.gps_for_navigation,
            descriptionResId = R.string.gps_for_navigation_description,
            isChecked = settings.useGpsForNavigation,
            onCheckedChanged = { isChecked ->
                settings.useGpsForNavigation = isChecked
                updateSettingsList() // Refresh the list to show the change
            }
        ))

        items.add(SettingItem.Divider) // Divider after General category

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))
        
        items.add(SettingItem.SettingEntry(
            stableId = "resolution",
            nameResId = R.string.resolution,
            value = Settings.Resolution.fromId(settings.resolutionId)?.resName ?: "",
            onClick = { _ ->
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_resolution)
                    .setSingleChoiceItems(Settings.Resolution.allRes, settings.resolutionId) { dialog, which ->
                        settings.resolutionId = which
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "dpiPixelDensity",
            nameResId = R.string.dpi,
            value = if (settings.dpiPixelDensity == 0) getString(R.string.auto) else settings.dpiPixelDensity.toString(),
            onClick = { _ ->
                val editView = EditText(requireContext())
                editView.inputType = InputType.TYPE_CLASS_NUMBER
                if (settings.dpiPixelDensity != 0) {
                    editView.setText(settings.dpiPixelDensity.toString())
                }

                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.enter_dpi_value)
                    .setView(editView)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        val inputText = editView.text.toString().trim()
                        val newDpi = inputText.toIntOrNull()
                        if (newDpi != null && newDpi >= 0) {
                            settings.dpiPixelDensity = newDpi
                        } else if (inputText.isNotEmpty()) {
                            Toast.makeText(activity, "Invalid DPI value. Please enter a number or 0 for auto.", Toast.LENGTH_LONG).show()
                        } else {
                            settings.dpiPixelDensity = 0 // If empty, set to auto
                        }
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.cancel()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            descriptionResId = R.string.start_in_fullscreen_mode_description,
            isChecked = settings.startInFullscreenMode,
            onCheckedChanged = { isChecked ->
                settings.startInFullscreenMode = isChecked
                updateSettingsList() // Refresh the list to show the change
            }
        ))

//        items.add(SettingItem.SettingEntry(                                                                                                                                                                                                   │
//            id = "customMargin",                                                                                                                                                                                                              │
//            nameResId = R.string.custom_margin,                                                                                                                                                                                               │
//            value = getString(R.string.custom_margin_description),                                                                                                                                                                            │
//            onClick = { _ ->                                                                                                                                                                                                                  │
//                val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.layout_custom_margin_dialog, null)                                                                                                                    │
//                val etLeft = dialogView.findViewById<EditText>(R.id.editTextLeft)                                                                                                                                                             │
//                val etTop = dialogView.findViewById<EditText>(R.id.editTextTop)                                                                                                                                                               │
//                val etRight = dialogView.findViewById<EditText>(R.id.editTextRight)                                                                                                                                                           │
//                val etBottom = dialogView.findViewById<EditText>(R.id.editTextBottom)                                                                                                                                                         │
//                                                                                                                                                                                                                                              │
//                // Set current values                                                                                                                                                                                                         │
//                etLeft.setText(settings.marginLeft.toString())                                                                                                                                                                                │
//                etTop.setText(settings.marginTop.toString())                                                                                                                                                                                  │
//                etRight.setText(settings.marginRight.toString())                                                                                                                                                                              │
//                etBottom.setText(settings.marginBottom.toString())                                                                                                                                                                            │
//                                                                                                                                                                                                                                              │
//                AlertDialog.Builder(requireContext())                                                                                                                                                                                         │
//                    .setTitle(R.string.enter_custom_margins)                                                                                                                                                                                  │
//                    .setView(dialogView)                                                                                                                                                                                                      │
//                    .setPositiveButton(android.R.string.ok) { dialog, _ ->                                                                                                                                                                    │
//                        val newLeft = etLeft.text.toString().toIntOrNull() ?: 0                                                                                                                                                               │
//                        val newTop = etTop.text.toString().toIntOrNull() ?: 0                                                                                                                                                                 │
//                        val newRight = etRight.text.toString().toIntOrNull() ?: 0                                                                                                                                                             │
//                        val newBottom = etBottom.text.toString().toIntOrNull() ?: 0                                                                                                                                                           │
//                                                                                                                                                                                                                                              │
//                        if (newLeft >= 0 && newTop >= 0 && newRight >= 0 && newBottom >= 0) {                                                                                                                                                 │
//                            settings.marginLeft = newLeft                                                                                                                                                                                     │
//                            settings.marginTop = newTop                                                                                                                                                                                       │
//                            settings.marginRight = newRight                                                                                                                                                                                   │
//                            settings.marginBottom = newBottom                                                                                                                                                                                 │
//                        } else {                                                                                                                                                                                                              │
//                            Toast.makeText(activity, "Invalid margin value. Please enter a non-negative number.", Toast.LENGTH_LONG).show()                                                                                                   │
//                        }                                                                                                                                                                                                                     │
//                        dialog.dismiss()                                                                                                                                                                                                      │
//                        updateSettingsList() // Refresh the list                                                                                                                                                                              │
//                    }                                                                                                                                                                                                                         │
//                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->                                                                                                                                                                │
//                        dialog.cancel()                                                                                                                                                                                                       │
//                    }                                                                                                                                                                                                                         │
//                    .show()                                                                                                                                                                                                                   │
//            }                                                                                                                                                                                                                                 │
//        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            value = if (settings.viewMode == Settings.ViewMode.SURFACE) getString(R.string.surface_view) else getString(R.string.texture_view),
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view))
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, settings.viewMode.value) { dialog, which ->
                        val newViewMode = Settings.ViewMode.fromInt(which)!!
                        settings.viewMode = newViewMode
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .show()
            }
        ))
        items.add(SettingItem.Divider) // Divider after Graphic category
        
        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "debugMode",
            nameResId = R.string.debug_mode,
            descriptionResId = R.string.debug_mode_description,
            isChecked = settings.debugMode,
            onCheckedChanged = { isChecked ->
                settings.debugMode = isChecked
                updateSettingsList() // Refresh the list to show the change
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "bluetoothAddress",
            nameResId = R.string.bluetooth_address_s,
            value = settings.bluetoothAddress.ifEmpty { getString(R.string.not_set) },
            onClick = { _ ->
                val editView = EditText(requireContext())
                editView.setText(settings.bluetoothAddress)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.enter_bluetooth_mac)
                    .setView(editView)
                    .setPositiveButton(android.R.string.ok) { dialog, _ ->
                        settings.bluetoothAddress = editView.text.toString().trim()
                        dialog.dismiss()
                        updateSettingsList() // Refresh the list
                    }
                    .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                        dialog.cancel()
                    }
                    .show()
            }
        ))

        settingsAdapter.submitList(items) // Submit the new list to ListAdapter
    }
}