package com.andrerinas.headunitrevived.aap

import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.Parcelable
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.connection.SocketAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.NightMode
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket

/**
 * @author algavris
 * *
 * @date 03/06/2016.
 */

class AapService : Service(), UsbReceiver.Listener {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private lateinit var nightModeReceiver: BroadcastReceiver

    private val transport: AapTransport
        get() = App.provide(this).transport

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        uiModeManager.nightMode = UiModeManager.MODE_NIGHT_AUTO

        usbReceiver = UsbReceiver(this)
        nightModeReceiver = NightModeReceiver(Settings(this), uiModeManager)

        val nightModeFilter = IntentFilter()
        nightModeFilter.addAction(Intent.ACTION_TIME_TICK)
        nightModeFilter.addAction(LocationUpdateIntent.action)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nightModeReceiver, nightModeFilter, RECEIVER_NOT_EXPORTED)
            registerReceiver(usbReceiver, UsbReceiver.createFilter(), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(nightModeReceiver, nightModeFilter)
            registerReceiver(usbReceiver, UsbReceiver.createFilter())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true) // Stop the foreground notification
        serviceJob.cancel()
        onDisconnect()
        unregisterReceiver(nightModeReceiver)
        unregisterReceiver(usbReceiver)
        uiModeManager.disableCarMode(0)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            AppLog.i("Stop action received, stopping service.")
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_START_SELF_MODE) {
            startSelfMode()
        } else {
            val localProxyPort = intent?.getIntExtra(EXTRA_LOCAL_PROXY_PORT, -1) ?: -1

            if (localProxyPort != -1) {
                AppLog.i("AapService started from WifiProxyService, connecting to local proxy on port $localProxyPort.")
                accessoryConnection = SocketAccessoryConnection("127.0.0.1", localProxyPort)
            } else {
                // Original logic for USB or direct Wi-Fi connection (if not using proxy)
                val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0
                if (connectionType == TYPE_WIFI) {
                    val ip = intent?.getStringExtra(EXTRA_IP) ?: ""
                    accessoryConnection = SocketAccessoryConnection(ip, 5277) // Direct Wi-Fi connection to 5277
                } else {
                    accessoryConnection = connectionFactory(intent, this) // USB connection
                }
            }

            if (accessoryConnection == null) {
                AppLog.e("Cannot create connection $intent")
                stopSelf()
                return START_NOT_STICKY
            }
        }


        uiModeManager.enableCarMode(0)

        val noty = NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setTicker("Headunit Revived is running")
            .setWhen(System.currentTimeMillis())
            .setContentTitle("Headunit Revived is running")
            .setContentText("...")
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, AapProjectionActivity.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) // Added FLAG_IMMUTABLE
            .build()

        startService(GpsLocationService.intent(this))

        startForeground(1, noty)

        if (intent?.action != ACTION_START_SELF_MODE) {
            serviceScope.launch {
                var connectionResult = false
                val maxRetries = 3
                var currentTry = 0

                withContext(Dispatchers.IO) {
                    while (currentTry < maxRetries && !connectionResult) {
                        currentTry++
                        AppLog.i("Connection attempt $currentTry of $maxRetries...")
                        connectionResult = accessoryConnection!!.connect()
                        if (!connectionResult && currentTry < maxRetries) {
                            AppLog.i("Connection failed, retrying in 2 seconds...")
                            delay(2000) // Wait 2 seconds before retrying
                        }
                    }
                }
                onConnectionResult(connectionResult)
            }
        }

        return START_STICKY
    }

    private fun startSelfMode() {
        // First, launch a background task to listen for the incoming connection
        serviceScope.launch(Dispatchers.IO) {
            try {
                val serverSocket = ServerSocket(5288)
                AppLog.i("Self-mode: Server listening on port 5288")
                
                // This will block until the client (AA) connects
                val clientSocket = serverSocket.accept()
                AppLog.i("Self-mode: Client connected from ${clientSocket.inetAddress}")
                serverSocket.close() // We only need one connection

                accessoryConnection = SocketAccessoryConnection(clientSocket)

                val connectionResult = accessoryConnection!!.connect()
                withContext(Dispatchers.Main) {
                    onConnectionResult(connectionResult)
                }

            } catch (e: Exception) {
                AppLog.e("Self-mode server socket failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Self-mode server failed: ${e.message}", Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }

        // Now, trigger the AA wireless setup activity
        serviceScope.launch(Dispatchers.Main) {
            delay(500) // Give the server socket a moment to start listening, just in case
            val magicalIntent = Intent().apply {
                setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1")
                putExtra("PARAM_SERVICE_PORT", 5288)
            }

            try {
                AppLog.i("Launching official Android Auto wireless setup for self-mode...")
                startActivity(magicalIntent)
            } catch (e: Exception) {
                AppLog.e("Failed to launch magical intent: ${e.message}", e)
                Toast.makeText(applicationContext, "Failed to start Android Auto. Is it installed?", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }
    }


    private suspend fun onConnectionResult(success: Boolean) {
        if (success) {
            // This is called on the main thread from serviceScope
            reset()
            val transportStarted = withContext(Dispatchers.IO) {
                // Switch to IO thread for network operation
                transport.start(accessoryConnection!!)
            }

            if (transportStarted) {
                isConnected = true
                sendBroadcast(ConnectedIntent())
            } else {
                AppLog.e("Transport start failed")
                Toast.makeText(applicationContext, "Could not start transport", Toast.LENGTH_SHORT).show()
                stopSelf()
            }
        } else {
            AppLog.e("Cannot connect to device")
            Toast.makeText(applicationContext, "Cannot connect to the device", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun onDisconnect() {
        isConnected = false
        sendBroadcast(DisconnectIntent())
        reset()
        accessoryConnection?.disconnect()
        accessoryConnection = null
    }

    private fun reset() {
        App.provide(this).resetTransport()
        App.provide(this).audioDecoder.stop()
        App.provide(this).videoDecoder.stop("AapService::reset")
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                stopSelf()
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {

    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {

    }

    private class NightModeReceiver(private val settings: Settings, private val modeManager: UiModeManager) : BroadcastReceiver() {
        private var nightMode = NightMode(settings, false)
        private var initialized = false
        private var lastValue = false

        override fun onReceive(context: Context, intent: Intent) {

            if (!nightMode.hasGPSLocation && intent.action == LocationUpdateIntent.action)
            {
                nightMode = NightMode(settings, true)
            }

            val isCurrent = nightMode.current
            if (!initialized || lastValue != isCurrent) {
                lastValue = isCurrent
                AppLog.i(nightMode.toString())
                initialized = App.provide(context).transport.send(NightModeEvent(isCurrent))
                if (initialized)
                {
                    modeManager.nightMode = if (isCurrent) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                }
            }
        }
    }

    companion object {
        var isConnected = false
        var selfMode = false
        const val ACTION_START_SELF_MODE = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE"
        const val ACTION_START_FROM_PROXY = "com.andrerinas.headunitrevived.ACTION_START_FROM_PROXY"
        const val EXTRA_LOCAL_PROXY_PORT = "local_proxy_port"
        const val ACTION_STOP_SERVICE = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE"
        private const val TYPE_USB = 1
        private const val TYPE_WIFI = 2
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type"
        private const val EXTRA_IP = "extra_ip"

        fun createIntent(device: UsbDevice, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(UsbManager.EXTRA_DEVICE, device)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB)
            return intent
        }

        fun createIntent(ip: String, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(EXTRA_IP, ip)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
            return intent
        }

        private fun connectionFactory(intent: Intent?, context: Context): AccessoryConnection? {

            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0

            if (connectionType == TYPE_USB) {
                val device = DeviceIntent(intent).device
                if (device == null) {
                    AppLog.e("No device in $intent")
                    return null
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                return UsbAccessoryConnection(usbManager, device)
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: ""
                return SocketAccessoryConnection(ip, 5277)
            }

            return null
        }
    }
}