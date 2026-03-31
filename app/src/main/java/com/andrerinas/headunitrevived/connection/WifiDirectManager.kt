package com.andrerinas.headunitrevived.connection

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import java.net.InetSocketAddress
import java.net.Socket

class WifiDirectManager(private val context: Context) : WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false
    private var isConnected = false
    private val handler = Handler(Looper.getMainLooper())

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String) -> Unit)? = null

    fun setCredentialsListener(callback: (String, String, String, String) -> Unit) {
        this.onCredentialsReady = callback
    }

    private val discoveryRunnable = object : Runnable {
        override fun run() {
            if (!isConnected) {
                startDiscovery()
                handler.postDelayed(this, 10000L) // Repeat every 10s to stay visible
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        if (com.andrerinas.headunitrevived.App.provide(context).settings.wifiConnectionMode != 3) {
                            AppLog.i("WifiDirectManager: Local name: ${it.deviceName}")
                        }
                        AapService.wifiDirectName.value = it.deviceName
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        AppLog.i("WifiDirectManager: Connected. Requesting info...")
                        manager?.requestConnectionInfo(channel, this@WifiDirectManager)
                    } else {
                        isConnected = false
                    }
                }
            }
        }
    }

    init {
        try {
            if (context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_WIFI_DIRECT)) {
                manager?.let { mgr ->
                    channel = mgr.initialize(context, context.mainLooper, null)
                    val filter = IntentFilter().apply {
                        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                    }
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                }
            }
        } catch (e: SecurityException) {
            AppLog.w("WifiDirectManager: WiFi Direct unavailable — permission denied: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        if (info.groupFormed) {
            isConnected = true
            isGroupOwner = info.isGroupOwner
            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            AppLog.i("WifiDirectManager: Group formed. Owner: $isGroupOwner, GO IP: $goIp")

            if (isGroupOwner) {
                // Request group info to get SSID and Passphrase, and check for connected clients
                manager?.requestGroupInfo(channel, this)
            } else if (info.groupOwnerAddress != null) {
                Thread {
                    var socket: Socket? = null
                    try {
                        AppLog.i("WifiDirectManager: Pinging Phone (GO) at $goIp to announce tablet...")
                        socket = Socket()
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, 5289), 2000)
                    } catch (e: Exception) {
                        AppLog.w("WifiDirectManager: Ping to GO failed: ${e.message}")
                    } finally {
                        try { socket?.close() } catch (e: Exception) {}
                    }
                }.start()
            }
        } else {
            AppLog.d("WifiDirectManager: onConnectionInfoAvailable: group not formed yet")
        }
    }

    private var groupInfoRetries = 0

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: android.net.wifi.p2p.WifiP2pGroup?) {
        if (group != null) {
            groupInfoRetries = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            // Dynamically determine the GO IP if possible, fallback to standard P2P range
            val ip = if (isGroupOwner) "192.168.49.1" else "unknown" // We'll rely on onConnectionInfoAvailable for real IP
            val bssid = getWifiDirectMac(group.`interface`)
            
            AppLog.i("WifiDirectManager: onGroupInfoAvailable: SSID: $ssid, BSSID: $bssid, GO: ${group.isGroupOwner}")
            
            val clients = group.clientList
            if (clients.isNotEmpty()) {
                AppLog.i("WifiDirectManager: Connected Clients (${clients.size}):")
                for (client in clients) {
                    AppLog.i("  - Client: ${client.deviceName} [${client.deviceAddress}] Status: ${client.status}")
                }
            } else {
                AppLog.d("WifiDirectManager: No clients connected to the group yet.")
            }

            if (isGroupOwner && ssid.isNotEmpty()) {
                // If we are the GO, we provide the credentials to the handshake manager
                AppLog.i("WifiDirectManager: Providing credentials to HandshakeManager. SSID=$ssid, IP=$ip")
                onCredentialsReady?.invoke(ssid, psk, ip, bssid)
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                AppLog.w("WifiDirectManager: Group info was null! Retrying in 1s (Attempt $groupInfoRetries/20)...")
                handler.postDelayed({
                    manager?.requestGroupInfo(channel, this)
                }, 1000L)
            } else {
                AppLog.e("WifiDirectManager: FATAL: Group info remained null after 20 retries.")
            }
        }
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (ifaceName != null && iface.name != ifaceName) continue
                if (ifaceName == null && !iface.name.contains("p2p")) continue
                
                val mac = iface.hardwareAddress
                if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    return sb.toString()
                }
            }
        } catch (e: Exception) {}
        return "00:00:00:00:00:00"
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start P2P discovery.")
            Toast.makeText(context, context.getString(R.string.wifi_disabled_info), Toast.LENGTH_LONG).show()
            return
        }

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "HURev", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name set to HURev") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        // 1. Stop any ongoing discovery and remove group to start fresh
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { removeGroupAndCreate() }
            override fun onFailure(reason: Int) { removeGroupAndCreate() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun removeGroupAndCreate() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { delayedCreateGroup(0) }
            override fun onFailure(reason: Int) { delayedCreateGroup(0) }
        })
    }

    private fun delayedCreateGroup(retryCount: Int) {
        handler.postDelayed({ createNewGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: P2P Group created.")
                isGroupOwner = true
                startDiscoveryLoop()
            }
            override fun onFailure(reason: Int) {
                if (reason == 2 && retryCount < 3) { // 2 = BUSY
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying in 2s...")
                    handler.postDelayed({ createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createGroup failed: $reason")
                }
            }
        })
    }

    private fun startDiscoveryLoop() {
        handler.removeCallbacks(discoveryRunnable)
        handler.post(discoveryRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { AppLog.d("WifiDirectManager: Discovery active") }
            override fun onFailure(reason: Int) { AppLog.w("WifiDirectManager: Discovery failed: $reason") }
        })
    }

    /**
     * Boomerang Hack: Briefly triggers system WiFi settings to wake up the radio.
     * Currently not used by default but kept in code for future use.
     */
    private fun triggerWifiSettings() {
        try {
            val intent = Intent().apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$WifiP2pSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }

        handler.postDelayed({
            try {
                val intent = Intent(context, com.andrerinas.headunitrevived.main.MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {}
        }, 800L)
    }

    @SuppressLint("MissingPermission")
    fun startNativeAaQuietHost() {
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start quiet P2P host.")
            return
        }

        AppLog.i("WifiDirectManager: Starting Native AA Host (Group Owner only, no discovery)")
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { delayedCreateQuietGroup(0) }
            override fun onFailure(reason: Int) { delayedCreateQuietGroup(0) }
        })
    }

    private fun delayedCreateQuietGroup(retryCount: Int) {
        handler.postDelayed({ createQuietGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createQuietGroup(retryCount: Int) {
        manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: Quiet P2P Group created. Waiting for phone...")
                isGroupOwner = true
                // Request info to dispatch credentials to NativeAaHandshakeManager
                manager?.requestGroupInfo(channel, this@WifiDirectManager)
            }
            override fun onFailure(reason: Int) {
                if (reason == 2 && retryCount < 3) {
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying quiet group in 2s...")
                    handler.postDelayed({ createQuietGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createQuietGroup failed: $reason")
                }
            }
        })
    }

    fun stop() {
        handler.removeCallbacks(discoveryRunnable)
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        if (isGroupOwner) {
            manager?.removeGroup(channel, null)
        }
    }
}
