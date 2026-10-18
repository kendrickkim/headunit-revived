package com.andrerinas.openheadunit.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.broker.AABrokerClient
import com.andrerinas.openheadunit.connection.broker.AABrokerProtocol
import com.andrerinas.openheadunit.connection.broker.AABrokerSession
import com.andrerinas.openheadunit.connection.broker.AABrokerStore
import com.andrerinas.openheadunit.connection.broker.WifiDirectDisplayInfo
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.google.android.material.appbar.MaterialToolbar

/**
 * Chooses the AABroker and sends the current Wi-Fi Direct group to it.
 */
class BrokerSettingsFragment : Fragment() {
    private lateinit var store: AABrokerStore
    private lateinit var scanner: AABrokerClient
    private val found = linkedMapOf<String, String>()
    private var scanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startScan()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_broker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = AABrokerStore(requireContext())
        scanner = AABrokerClient(requireContext())
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val adapter = SettingsAdapter()
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        val toolbar = view.findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { leave() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        fun render() {
            adapter.submitList(rows())
        }
        AABrokerSession.setListener { render() }
        render()
    }

    override fun onDestroyView() {
        scanner.stopScan()
        AABrokerSession.setListener(null)
        super.onDestroyView()
    }

    private fun rows(): List<SettingItem> {
        val items = mutableListOf<SettingItem>()
        val saved = store.deviceName ?: store.deviceAddress
        items.add(SettingItem.SettingEntry(
            stableId = "brokerSaved",
            nameResId = R.string.broker_saved_device,
            value = saved ?: getString(R.string.broker_none),
            onClick = { _ -> },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerStatus",
            nameResId = R.string.broker_status,
            value = statusText(),
            onClick = { _ -> },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerScan",
            nameResId = R.string.broker_scan,
            value = if (scanning) getString(R.string.broker_scanning) else getString(R.string.broker_scan_action),
            onClick = { requestScan() },
        ))
        found.forEach { (address, name) ->
            items.add(SettingItem.SettingEntry(
                stableId = "broker-$address",
                nameResId = R.string.broker_found_device,
                nameOverride = name,
                value = address,
                onClick = { select(address, name) },
            ))
        }
        items.add(SettingItem.SettingEntry(
            stableId = "brokerPair",
            nameResId = R.string.broker_pair,
            value = getString(R.string.broker_pair_value),
            isEnabled = store.deviceAddress != null,
            onClick = { AABrokerSession.openPairing(requireContext()); refresh() },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerSend",
            nameResId = R.string.broker_send_now,
            value = getString(R.string.broker_send_now_value),
            isEnabled = store.deviceAddress != null,
            onClick = { sendNow() },
        ))
        items.add(SettingItem.SettingEntry(
            stableId = "brokerForget",
            nameResId = R.string.broker_forget,
            value = getString(R.string.broker_forget_value),
            isEnabled = store.deviceAddress != null,
            onClick = {
                store.clear()
                AABrokerSession.close()
                found.clear()
                refresh()
            },
        ))
        return items
    }

    private fun statusText(): String {
        val status = AABrokerSession.current()
        return when (status.phase) {
            AABrokerSession.Phase.IDLE -> getString(R.string.broker_phase_idle)
            AABrokerSession.Phase.CONNECTING -> getString(R.string.broker_phase_connecting)
            AABrokerSession.Phase.READY -> getString(R.string.broker_phase_ready)
            AABrokerSession.Phase.INCOMPLETE -> getString(R.string.broker_phase_incomplete)
            AABrokerSession.Phase.PAIRING -> getString(R.string.broker_phase_pairing)
            AABrokerSession.Phase.FAILED -> getString(R.string.broker_phase_failed, status.detail)
        }
    }

    private fun requestScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) needed += Manifest.permission.BLUETOOTH_SCAN
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) needed += Manifest.permission.ACCESS_FINE_LOCATION
        if (needed.isEmpty()) startScan() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startScan() {
        scanning = true
        found.clear()
        refresh()
        scanner.scan(
            onDevice = { device, name ->
                val label = name?.trim().orEmpty()
                if (!label.startsWith(AABrokerProtocol.BLE_NAME_PREFIX)) return@scan
                found[device.address] = label
                refresh()
            },
            onFinished = {
                scanning = false
                refresh()
            },
        )
    }

    private fun select(address: String, name: String) {
        store.deviceAddress = address
        store.deviceName = name
        scanner.stopScan()
        scanning = false
        sendCurrentGroup(pairIfIncomplete = true)
    }

    private fun sendNow() {
        sendCurrentGroup(pairIfIncomplete = false)
    }

    private fun sendCurrentGroup(pairIfIncomplete: Boolean) {
        WifiDirectDisplayInfo.read(requireContext()) { info ->
            if (info.isComplete || !pairIfIncomplete) {
                AABrokerSession.offer(requireContext(), info)
            } else {
                AABrokerSession.openPairing(requireContext())
            }
            refresh()
        }
    }

    private fun refresh() {
        val recycler = view?.findViewById<RecyclerView>(R.id.recycler_view) ?: return
        (recycler.adapter as? SettingsAdapter)?.submitList(rows())
    }

    private fun granted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun leave() {
        try {
            if (!findNavController().navigateUp()) requireActivity().finish()
        } catch (_: Exception) {
            requireActivity().finish()
        }
    }
}
