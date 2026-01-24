package com.andrerinas.headunitrevived.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.aap.protocol.messages.NightModeEvent
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.connection.SocketAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbAccessoryConnection
import com.andrerinas.headunitrevived.connection.UsbDeviceCompat
import com.andrerinas.headunitrevived.connection.UsbReceiver
import com.andrerinas.headunitrevived.contract.ConnectedIntent
import com.andrerinas.headunitrevived.contract.DisconnectIntent
import com.andrerinas.headunitrevived.contract.LocationUpdateIntent
import com.andrerinas.headunitrevived.location.GpsLocationService
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.DeviceIntent
import com.andrerinas.headunitrevived.utils.NightMode
import com.andrerinas.headunitrevived.utils.Settings
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

class AapService : Service(), UsbReceiver.Listener {
    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var uiModeManager: UiModeManager
    private var accessoryConnection: AccessoryConnection? = null
    private lateinit var usbReceiver: UsbReceiver
    private lateinit var nightModeReceiver: BroadcastReceiver
    private var wirelessServer: WirelessServer? = null

    private var pendingConnectionType: String = ""
    private var pendingConnectionIp: String = ""
    private var pendingConnectionUsbDevice: String = ""
    private val connectionAttemptId = AtomicInteger(0)

    private val transport: AapTransport
        get() = App.provide(this).transport

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.i("AapService creating...");

        startForeground(1, createNotification());

        uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager;
        uiModeManager.enableCarMode(0);
        uiModeManager.nightMode = UiModeManager.MODE_NIGHT_AUTO;

        usbReceiver = UsbReceiver(this);
        nightModeReceiver = NightModeReceiver(Settings(this), uiModeManager);

        val nightModeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK);
            addAction(LocationUpdateIntent.action);
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(nightModeReceiver, nightModeFilter, RECEIVER_NOT_EXPORTED);
            registerReceiver(usbReceiver, UsbReceiver.createFilter(), RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(nightModeReceiver, nightModeFilter);
            registerReceiver(usbReceiver, UsbReceiver.createFilter());
        }

        startService(GpsLocationService.intent(this));

        if (App.provide(this).settings.wifiLauncherMode) {
            startWirelessServer();
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, App.defaultChannel)
            .setSmallIcon(R.drawable.ic_stat_aa)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentTitle("Headunit Revived")
            .setContentText("Headunit Revived is running")
            .setContentIntent(PendingIntent.getActivity(this, 0, 
                Intent(this, com.andrerinas.headunitrevived.main.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build();
    }

    override fun onDestroy() {
        AppLog.i("AapService destroying...");
        stopWirelessServer();
        serviceJob.cancel();
        onDisconnect();
        unregisterReceiver(nightModeReceiver);
        unregisterReceiver(usbReceiver);
        uiModeManager.disableCarMode(0);
        super.onDestroy();
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                AppLog.i("Stop action received.");
                stopSelf();
                return START_NOT_STICKY;
            }
            ACTION_START_SELF_MODE -> {
                startSelfMode();
            }
            ACTION_START_WIRELESS -> {
                startWirelessServer();
            }
            ACTION_STOP_WIRELESS -> {
                stopWirelessServer();
            }
            else -> {
                handleConnectionIntent(intent);
            }
        }
        return START_STICKY;
    }

    private fun handleConnectionIntent(intent: Intent?) {
        val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
        if (connectionType == 0) return ;

        accessoryConnection = connectionFactory(intent, this);
        if (accessoryConnection == null) {
            AppLog.e("Cannot create connection from intent");
            return;
        }

        when (connectionType) {
            TYPE_USB -> {
                val device = DeviceIntent(intent).device;
                pendingConnectionType = Settings.CONNECTION_TYPE_USB;
                pendingConnectionIp = "";
                pendingConnectionUsbDevice = if (device != null) UsbDeviceCompat.getUniqueName(device) else "";
            }
            TYPE_WIFI -> {
                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                pendingConnectionIp = intent?.getStringExtra(EXTRA_IP) ?: "";
                pendingConnectionUsbDevice = "";
            }
        }

        // Capture the connection instance so we pass the exact object to the result handler
        val conn = accessoryConnection;
        val attemptId = connectionAttemptId.incrementAndGet();

        serviceScope.launch {
            var connectionResult = false;
            withContext(Dispatchers.IO) {
                connectionResult = conn?.connect() ?: false;
            }
            onConnectionResult(connectionResult, attemptId, conn);
        }
    }

    private fun startWirelessServer() {
        if (wirelessServer != null) return;
        wirelessServer = WirelessServer().apply { start() };
    }

    private fun stopWirelessServer() {
        wirelessServer?.stopServer();
        wirelessServer = null;
    }

    private inner class WirelessServer : Thread() {
        private var serverSocket: ServerSocket? = null;
        private var nsdManager: NsdManager? = null;
        private var registrationListener: NsdManager.RegistrationListener? = null;
        private var running = true;

        override fun run() {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager;
            registerNsd();

            try {
                serverSocket = ServerSocket(5288).apply { reuseAddress = true };
                AppLog.i("Wireless Server listening on port 5288");

                while (running) {
                    val clientSocket = serverSocket?.accept();
                    if (clientSocket != null) {
                        AppLog.i("Wireless client connected: ${clientSocket.inetAddress}");
                        
                        serviceScope.launch(Dispatchers.Main) {
                            if (isConnected) {
                                AppLog.w("Already connected, dropping wireless client");
                                clientSocket.close();
                            } else {
                                pendingConnectionType = Settings.CONNECTION_TYPE_WIFI;
                                pendingConnectionIp = clientSocket.inetAddress.hostAddress ?:"";
                                pendingConnectionUsbDevice = "";

                                // Prepare and capture the connection instance
                                val conn = SocketAccessoryConnection(clientSocket);
                                accessoryConnection = conn;

                                // mark this attempt before starting the blocking connect
                                val attemptId = connectionAttemptId.incrementAndGet();
                                val success = conn.connect();

                                onConnectionResult(success, attemptId, conn);
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.e("Wireless server error", e);
            } finally {
                unregisterNsd();
            }
        }

        private fun registerNsd() {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "AAWireless";
                serviceType = "_aawireless._tcp";
                port = 5288;
            };
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("NSD Registered: ${info.serviceName}");
                override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Reg Fail: $err");
                override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("NSD Unregistered");
                override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) = AppLog.e("NSD Unreg Fail: $err");
            };
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        }

        private fun unregisterNsd() {
            registrationListener?.let { nsdManager?.unregisterService(it) };
            registrationListener = null;
        }

        fun stopServer() {
            running = false;
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    private fun startSelfMode() {
        startWirelessServer();

        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager;
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivityManager.activeNetwork else null;
        val networkToUse = activeNetwork ?: createFakeNetwork(99999);
        val fakeWifiInfo = createFakeWifiInfo();

        val magicalIntent = Intent().apply {
            setClassName("com.google.android.projection.gearhead", "com.google.android.apps.auto.wireless.setup.service.impl.WirelessStartupActivity");
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            putExtra("PARAM_HOST_ADDRESS", "127.0.0.1");
            putExtra("PARAM_SERVICE_PORT", 5288);
            networkToUse?.let { putExtra("PARAM_SERVICE_WIFI_NETWORK", it) };
            fakeWifiInfo?.let { putExtra("wifi_info", it) };
        };

        try {
            AppLog.i("Launching AA Wireless Startup...");
            startActivity(magicalIntent);
        } catch (e: Exception) {
            AppLog.e("Failed to launch AA", e);
            Toast.makeText(this, "Failed to start Android Auto", Toast.LENGTH_SHORT).show();
        }
    }

    private fun createFakeNetwork(netId: Int): Parcelable? {
        val parcel = Parcel.obtain();
        return try {
            parcel.writeInt(netId);
            parcel.setDataPosition(0);
            val creator = Class.forName("android.net.Network").getField("CREATOR").get(null) as Parcelable.Creator<*>;
            creator.createFromParcel(parcel) as Parcelable;
        } catch (e: Exception) { null } finally { parcel.recycle() }
    }

    private fun createFakeWifiInfo(): Parcelable? {
        return try {
            val wifiInfoClass = Class.forName("android.net.wifi.WifiInfo");
            val wifiInfo = wifiInfoClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as Parcelable;
            try {
                wifiInfoClass.getDeclaredField("mSSID").apply { isAccessible = true }.set(wifiInfo, "\"Headunit-Fake-Wifi\"");
            } catch (e: Exception) {}
            wifiInfo;
        } catch (e: Exception) { null }
    }

    private suspend fun onConnectionResult(success: Boolean, attemptId: Int, connection: AccessoryConnection?) {
        if (attemptId != connectionAttemptId.get()) {
            AppLog.w("onConnectionResult: stale attempt $attemptId, current ${connectionAttemptId.get()}");
            return;
        }
        val activeConnection = connection ?: run {
            AppLog.w("onConnectionResult: accessoryConnection cleared before transport start (attempt $attemptId)");
            return;
        }

        if (success) {
            reset();
            val transportStarted = withContext(Dispatchers.IO) {
                transport.start(activeConnection);
            }

            if (transportStarted) {
                isConnected = true;
                sendBroadcast(ConnectedIntent());
                
                if (pendingConnectionType.isNotEmpty()) {
                    val settings = App.provide(this).settings;
                    settings.saveLastConnection(
                        type = pendingConnectionType,
                        ip = pendingConnectionIp,
                        usbDevice = pendingConnectionUsbDevice
                    );
                    AppLog.i("Saved last connection: type=$pendingConnectionType, ip=$pendingConnectionIp, usb=$pendingConnectionUsbDevice");
                    pendingConnectionType = "";
                    pendingConnectionIp = "";
                    pendingConnectionUsbDevice = "";
                }

                val aapIntent = AapProjectionActivity.intent(this@AapService).apply {
                    putExtra(AapProjectionActivity.EXTRA_FOCUS, true);
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                };
                startActivity(aapIntent);
            } else {
                stopSelf();
            }
        }
    }

    private fun onDisconnect() {
        isConnected = false;
        sendBroadcast(DisconnectIntent());
        reset();
        accessoryConnection?.disconnect();
        accessoryConnection = null;
        // Invalidate any in-flight attempts
        connectionAttemptId.incrementAndGet();
    }

    private fun reset() {
        App.provide(this).resetTransport();
        App.provide(this).audioDecoder.stop();
        App.provide(this).videoDecoder.stop("AapService::reset");
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (accessoryConnection is UsbAccessoryConnection) {
            if ((accessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                onDisconnect();
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {}
    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {}

    private class NightModeReceiver(private val settings: Settings, private val modeManager: UiModeManager) : BroadcastReceiver() {
        private var nightMode = NightMode(settings, false);
        private var initialized = false;
        private var lastValue = false;

        override fun onReceive(context: Context, intent: Intent) {
            if (!nightMode.hasGPSLocation && intent.action == LocationUpdateIntent.action) {
                nightMode = NightMode(settings, true);
            }
            val isCurrent = nightMode.current;
            if (!initialized || lastValue != isCurrent) {
                lastValue = isCurrent;
                initialized = App.provide(context).transport.send(NightModeEvent(isCurrent));
                if (initialized) {
                    modeManager.nightMode = if (isCurrent) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO;
                }
            }
        }
    }

    companion object {
        var isConnected = false;
        var selfMode = false;
        const val ACTION_START_SELF_MODE = "com.andrerinas.headunitrevived.ACTION_START_SELF_MODE";
        const val ACTION_START_WIRELESS = "com.andrerinas.headunitrevived.ACTION_START_WIRELESS";
        const val ACTION_STOP_WIRELESS = "com.andrerinas.headunitrevived.ACTION_STOP_WIRELESS";
        const val ACTION_STOP_SERVICE = "com.andrerinas.headunitrevived.ACTION_STOP_SERVICE";
        private const val TYPE_USB = 1;
        private const val TYPE_WIFI = 2;
        private const val EXTRA_CONNECTION_TYPE = "extra_connection_type";
        private const val EXTRA_IP = "extra_ip";

        fun createIntent(device: UsbDevice, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(UsbManager.EXTRA_DEVICE, device);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB);
            };
        }

        fun createIntent(ip: String, context: Context): Intent {
            return Intent(context, AapService::class.java).apply {
                putExtra(EXTRA_IP, ip);
                putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI);
            };
        }

        private fun connectionFactory(intent: Intent?, context: Context): AccessoryConnection? {
            val connectionType = intent?.getIntExtra(EXTRA_CONNECTION_TYPE, 0) ?: 0;
            if (connectionType == TYPE_USB) {
                val device = DeviceIntent(intent).device ?: return null;
                return UsbAccessoryConnection(context.getSystemService(Context.USB_SERVICE) as UsbManager, device);
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent?.getStringExtra(EXTRA_IP) ?: "";
                return SocketAccessoryConnection(ip, 5277);
            }
            return null;
        }
    }
}