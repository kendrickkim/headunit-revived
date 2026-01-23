package com.andrerinas.headunitrevived.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapProjectionActivity
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings

class HomeFragment : Fragment() {

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView
    private var hasAttemptedAutoConnect = false

    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.i("HomeFragment received ${intent?.action}")
            updateProjectionButtonText()

            if (intent?.action == ConnectedIntent.action) {
                AppLog.i("HomeFragment: Connected broadcast received, launching projection")
                val aapIntent = AapProjectionActivity.intent(requireContext()).apply {
                    putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(aapIntent)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check Safety Disclaimer
        if (!App.provide(requireContext()).settings.hasAcceptedDisclaimer) {
            SafetyDisclaimerDialog.show(childFragmentManager)
        }

        self_mode_button = view.findViewById(R.id.self_mode_button)
        usb = view.findViewById(R.id.usb_button)
        settings = view.findViewById(R.id.settings_button)
        wifi = view.findViewById(R.id.wifi_button)
        exitButton = view.findViewById(R.id.exit_button)
        self_mode_text = view.findViewById(R.id.self_mode_text)

        setupListeners()
        updateProjectionButtonText()

        val appSettings = App.provide(requireContext()).settings

        // 1. Priority: Auto-Connect last session (WiFi/USB)
        if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !AapService.isConnected) {
            hasAttemptedAutoConnect = true
            attemptAutoConnect()
        }

        // 2. Priority: Auto-Start Self Mode
        if (appSettings.autoStartSelfMode && !hasAutoStarted && !AapService.isConnected) {
            hasAutoStarted = true
            startSelfMode()
        }
    }

    private fun startSelfMode() {
        AapService.selfMode = true
        AapService.isConnected = false
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        requireContext().startService(intent)
        AppLog.i("Auto start selfmode")
        //Toast.makeText(requireContext(), "Starting Self Mode...", Toast.LENGTH_SHORT).show()
    }

    private fun attemptAutoConnect() {
        val appSettings = App.provide(requireContext()).settings

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            AapService.isConnected) {
            return
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return
        }

        when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                val ip = appSettings.lastConnectionIp
                if (ip.isNotEmpty()) {
                    AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                    Toast.makeText(requireContext(), "Auto-connecting to $ip...", Toast.LENGTH_SHORT).show()
                    requireContext().startService(AapService.createIntent(ip, requireContext()))
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), "Auto-connecting to USB device...", Toast.LENGTH_SHORT).show()
                        requireContext().startService(AapService.createIntent(matchingDevice, requireContext()))
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            requireContext().startService(stopServiceIntent)
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (AapService.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                startSelfMode()
            }
        }

        usb.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_usbListFragment)
        }

        settings.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_settingsFragment)
        }

        wifi.setOnClickListener {
            findNavController().navigate(R.id.action_homeFragment_to_networkListFragment)
        }
    }

    private fun updateProjectionButtonText() {
        if (AapService.isConnected) {
            self_mode_text.text = getString(R.string.to_android_auto)
        } else {
            self_mode_text.text = getString(R.string.self_mode)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${AapService.isConnected}")
        val filter = IntentFilter().apply {
            addAction(ConnectedIntent.action)
            addAction(DisconnectIntent.action)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(connectionStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            requireContext().registerReceiver(connectionStatusReceiver, filter)
        }
        updateProjectionButtonText()
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(connectionStatusReceiver)
    }

    companion object {
        private var hasAutoStarted = false
        fun resetAutoStart() {
            hasAutoStarted = false
        }
    }
}
