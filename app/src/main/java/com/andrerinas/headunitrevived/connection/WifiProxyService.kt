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
            serverSocket = ServerSocket(PROXY_SERVER_PORT)
            serverSocket?.reuseAddress = true
            AppLog.i("Proxy Server listening on port $PROXY_SERVER_PORT for phone connection...")

            while (isActive && isRunning.get()) {
                try {
                    val phoneSocket = serverSocket?.accept() // Blocks until the PHONE connects
                    if (phoneSocket != null) {
                        AppLog.i("Phone connected from ${phoneSocket.inetAddress}:${phoneSocket.port}")
                        // Handle this connection which involves waiting for a second client
                        handleClientConnection(phoneSocket)
                        AppLog.i("Connection handling finished. Ready for next phone connection.")
                    }
                } catch (e: IOException) {
                    if (!isRunning.get() || serverSocket?.isClosed == true) {
                        AppLog.i("Server socket closed, stopping accept loop.")
                        break
                    }
                    AppLog.e("Error accepting phone connection: ${e.message}")
                    delay(2000) // Wait a bit before trying to accept again
                }
            }
        } catch (e: IOException) {
            if (isActive && isRunning.get()) {
                AppLog.e("Proxy Server socket error: ${e.message}. Port $PROXY_SERVER_PORT might be in use.")
            }
        } finally {
            stopServerSocket()
            AppLog.i("Proxy Server has shut down.")
        }
    }

    private fun stopServerSocket() {
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (e: IOException) {
            AppLog.e("Error closing proxy server socket: ${e.message}")
        }
    }

    private fun handleClientConnection(phoneSocket: Socket) = serviceScope.launch {
        var aapServiceSocket: Socket? = null
        try {
            // 1. Start AapService, telling it to connect to our main port 5288
            AppLog.i("Starting AapService, instructing it to connect to 127.0.0.1:$PROXY_SERVER_PORT")
            val aapServiceIntent = Intent(this@WifiProxyService, AapService::class.java).apply {
                action = AapService.ACTION_START_FROM_PROXY
                // Tell AapService to connect to our main proxy port on localhost
                putExtra(AapService.EXTRA_LOCAL_PROXY_PORT, PROXY_SERVER_PORT)
                putExtra("PARAM_HOST_ADDRESS", "127.0.0.1") // Based on decompiled code
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startService(aapServiceIntent)
            AppLog.i("Started AapService.")

            // 2. Accept the second connection (from AapService) on the SAME server socket
            AppLog.i("Waiting for local AapService to connect on port $PROXY_SERVER_PORT...")
            serverSocket?.soTimeout = 15000 // 15 second timeout to prevent getting stuck
            aapServiceSocket = serverSocket?.accept()
            serverSocket?.soTimeout = 0 // Reset timeout to infinite for the next phone connection

            if (aapServiceSocket == null) {
                throw IOException("AapService did not connect in time.")
            }
            AppLog.i("AapService connected locally from ${aapServiceSocket.inetAddress}:${aapServiceSocket.port}.")

            // 3. Bidirectional proxying between phoneSocket and aapServiceSocket
            AppLog.i("Starting bidirectional proxy between phone and AapService.")
            val phoneToAapJob = launch { phoneSocket.inputStream.copyTo(aapServiceSocket.outputStream) }
            val aapToPhoneJob = launch { aapServiceSocket.inputStream.copyTo(phoneSocket.outputStream) }

            // Wait for both directions to complete or fail
            joinAll(phoneToAapJob, aapToPhoneJob)

        } catch (e: java.net.SocketTimeoutException) {
            AppLog.e("Timeout: AapService failed to connect to the proxy within 15 seconds.")
        } catch (e: IOException) {
            AppLog.e("Proxying error: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("Unexpected proxying error: ${e.message}")
        } finally {
            // Close sockets in separate try-catch blocks
            try {
                phoneSocket.close()
                AppLog.i("Phone socket closed.")
            } catch (e: IOException) {
                AppLog.e("Error closing phone socket: ${e.message}")
            }
            try {
                aapServiceSocket?.close()
                AppLog.i("AapService socket closed.")
            } catch (e: IOException) {
                AppLog.e("Error closing AapService socket: ${e.message}")
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
