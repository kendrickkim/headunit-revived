package com.andrerinas.headunitrevived.main

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.utils.AppLog

class HomeFragment : Fragment() {

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView

    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLog.i("HomeFragment received ${intent?.action}")
            updateProjectionButtonText()

            if (intent?.action == com.andrerinas.headunitrevived.contract.ConnectedIntent.action) {
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
                AapService.selfMode = true
                AapService.isConnected = false
                val intent = Intent(requireContext(), AapService::class.java)
                intent.action = AapService.ACTION_START_SELF_MODE
                requireContext().startService(intent)
                Toast.makeText(requireContext(), "Starting Self Mode...", Toast.LENGTH_SHORT).show()
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

        if (AapService.isConnected) {
            AppLog.i("HomeFragment onResume: Connected, launching projection")
            val aapIntent = AapProjectionActivity.intent(requireContext()).apply {
                putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(aapIntent)
        }
    }

    override fun onPause() {
        super.onPause()
        requireContext().unregisterReceiver(connectionStatusReceiver)
    }
}
