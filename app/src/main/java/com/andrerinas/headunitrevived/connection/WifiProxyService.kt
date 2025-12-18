package com.andrerinas.headunitrevived.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.andrerinas.headunitrevived.aap.AapService
import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import android.app.Service
import android.content.Intent
import android.os.IBinder

class WifiProxyService : Service() {

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var serverSocket: ServerSocket? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val isRunning = AtomicBoolean(false)

    // Port for the official Android Auto client to connect to (from Wifi Launcher)
    private val PROXY_SERVER_PORT = 5288
    // Port for the internal AapTransport to listen on
    private val AAP_TRANSPORT_PORT = 5277
    private val AAP_TRANSPORT_HOST = "127.0.0.1"

    override fun onBind(intent: Intent?): IBinder? {
        return null // Not a bound service
    }

    override fun onCreate() {
        super.onCreate()
        AppLog.d("WifiProxyService: onCreate called.") // NEW LOG
        AppLog.i("WifiProxyService created.")
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppLog.i("WifiProxyService onStartCommand.")
        if (isRunning.getAndSet(true)) {
            AppLog.w("WifiProxyService is already running.")
            return START_STICKY
        }
        startNsdRegistration()
        startServerSocket()
        return START_STICKY // Keep service running
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.i("WifiProxyService destroyed.")
        isRunning.set(false)
        stopNsdRegistration()
        stopServerSocket()
        serviceScope.cancel() // Cancel all coroutines
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            AppLog.w("WifiProxyService is not running.")
            return
        }
        AppLog.i("Stopping WifiProxyService...")
        stopNsdRegistration()
        stopServerSocket()
        serviceScope.cancel() // Cancel all coroutines
    }

    private fun startNsdRegistration() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "AAWireless" // Name as seen in TestImported/app/src/main/java/v3/g.java
            serviceType = "_aawireless._tcp" // Type as seen in TestImported/app/src/main/java/v3/g.java
            port = PROXY_SERVER_PORT
        }
        AppLog.i("Attempting NSD registration for service ${serviceInfo.serviceName} on port ${serviceInfo.port}") // Changed to AppLog.i

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                AppLog.i("NSD Service registered: ${NsdServiceInfo.serviceName} on port ${NsdServiceInfo.port}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.e("NSD Registration failed: $errorCode")
                // Retry registration after a delay
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isRunning.get()) startNsdRegistration()
                }, 5000)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                AppLog.i("NSD Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                AppLog.e("NSD Unregistration failed: $errorCode")
            }
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    private fun stopNsdRegistration() {
        registrationListener?.let {
            try {
                nsdManager?.unregisterService(it)
            } catch (e: IllegalArgumentException) {
                AppLog.e("NSD Unregistration failed: ${e.message}")
            }
        }
        registrationListener = null
    }

    private fun startServerSocket(): Job = serviceScope.launch {
        try {
            // Check if port is available before trying to bind
            val tempSocket = ServerSocket()
            tempSocket.reuseAddress = true
            tempSocket.bind(InetSocketAddress(PROXY_SERVER_PORT))
            tempSocket.close()
            AppLog.i("Port $PROXY_SERVER_PORT is available.")

            serverSocket = ServerSocket(PROXY_SERVER_PORT)
            serverSocket?.reuseAddress = true
            AppLog.i("Proxy Server listening on port $PROXY_SERVER_PORT")
            while (isActive && isRunning.get()) {
                val clientSocket = serverSocket?.accept() // This blocks until a connection is made
                if (clientSocket != null) {
                    AppLog.i("WiFiLauncher Client connected from ${clientSocket.inetAddress}:${clientSocket.port}")
                    handleClientConnection(clientSocket)
                }
            }
        } catch (e: IOException) {
            if (isActive && isRunning.get()) { // Only log if not intentionally stopped
                AppLog.e("Proxy Server socket error: ${e.message}. Port $PROXY_SERVER_PORT might be in use.")
                // Attempt to restart server socket after a delay
                delay(5000)
                startServerSocket()
            } else {
                AppLog.i("Proxy Server socket closed.")
            }
        } catch (e: Exception) {
            AppLog.e("Proxy Server unexpected error: ${e.message}")
            if (isActive && isRunning.get()) {
                delay(5000)
                startServerSocket()
            }
        } finally {
            serverSocket?.close()
            serverSocket = null
        }
    }

    private fun stopServerSocket() {
        try {
            serverSocket?.close()
        } catch (e: IOException) {
            AppLog.e("Error closing proxy server socket: ${e.message}")
        }
    }

    private fun handleClientConnection(clientSocket: Socket): Job = serviceScope.launch {
        var localServerSocket: ServerSocket? = null
        var aapTransportClientSocket: Socket? = null
        try {
            // 1. Open a local server socket for AapService to connect to
            localServerSocket = ServerSocket(0) // Use 0 for an ephemeral port
            val localPort = localServerSocket.localPort
            AppLog.i("Opened local server socket on port $localPort for AapService.")

            // 2. Start AapService, telling it to connect to this local port
            val aapServiceIntent = Intent(this@WifiProxyService, AapService::class.java).apply {
                action = AapService.ACTION_START_FROM_PROXY
                putExtra(AapService.EXTRA_LOCAL_PROXY_PORT, localPort)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Required if starting activity from service
            }
            startService(aapServiceIntent)
            AppLog.i("Started AapService with local proxy port $localPort.")

            // 3. Accept connection from AapService
            aapTransportClientSocket = localServerSocket.accept()
            AppLog.i("AapService connected to local proxy on port $localPort.")

            // 4. Bidirectional proxying between clientSocket (from phone) and aapTransportClientSocket (from AapService)
            val clientToAapJob = launch {
                clientSocket.getInputStream().copyTo(aapTransportClientSocket.getOutputStream())
            }
            val aapToClientJob = launch {
                aapTransportClientSocket.getInputStream().copyTo(clientSocket.getOutputStream())
            }

            // Wait for both directions to complete or fail
            joinAll(clientToAapJob, aapToClientJob)

        } catch (e: IOException) {
            AppLog.e("Proxying error: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("Unexpected proxying error: ${e.message}")
        } finally {
            try {
                clientSocket.close()
                AppLog.i("Client socket closed.")
            } catch (e: IOException) {
                AppLog.e("Error closing client socket: ${e.message}")
            }
            try {
                aapTransportClientSocket?.close()
                AppLog.i("AapService client socket closed.")
            } catch (e: IOException) {
                AppLog.e("Error closing AapService client socket: ${e.message}")
            }
            try {
                localServerSocket?.close()
                AppLog.i("Local server socket closed.")
            } catch (e: IOException) {
                AppLog.e("Error closing local server socket: ${e.message}")
            }
        }
    }
}

// Extension function to copy streams
private fun java.io.InputStream.copyTo(out: java.io.OutputStream, bufferSize: Int = DEFAULT_BUFFER_SIZE) {
    val buffer = ByteArray(bufferSize)
    var bytesRead: Int
    while (true) {
        bytesRead = read(buffer)
        if (bytesRead == -1) break
        out.write(buffer, 0, bytesRead)
        out.flush() // Ensure data is sent immediately
    }
}
