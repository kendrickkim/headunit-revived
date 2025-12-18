package com.andrerinas.headunitrevived.connection


import com.andrerinas.headunitrevived.utils.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * @author algavris
 * *
 * @date 05/11/2016.
 */
class SocketAccessoryConnection(private val ip: String, private val port: Int) : AccessoryConnection {
    private var output: OutputStream? = null
    private var input: DataInputStream? = null
    private var transport = Socket()

    override val isSingleMessage: Boolean
        get() = true

    override fun sendBlocking(buf: ByteArray, length: Int, timeout: Int): Int {
        return try {
            output!!.write(buf, 0, length)
            output!!.flush()
            length
        } catch (e: IOException) {
            AppLog.e(e)
            -1
        }
    }

    override fun recvBlocking(buf: ByteArray, length: Int, timeout: Int, readFully: Boolean): Int {
        return try {
            if (readFully) {
                input!!.readFully(buf,0, length)
                length
            } else {
                input!!.read(buf, 0, length)
            }
        } catch (e: IOException) {
            -1
        }
    }

    override val isConnected: Boolean
        get() = transport.isConnected

    override suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            transport.soTimeout = 15000
            transport.connect(InetSocketAddress(ip, port), 5000)
            transport.tcpNoDelay = true
            input = DataInputStream(transport.getInputStream())
            output = transport.getOutputStream()
            return@withContext true
        } catch (e: IOException) {
            AppLog.e(e)
            return@withContext false
        }
    }

    override fun disconnect() {
        if (transport.isConnected) {
            try {
                transport.close()
            } catch (e: IOException) {
                AppLog.e(e)
            }

        }
        input = null
        output = null
    }

    companion object {
        private const val DEF_BUFFER_LENGTH = 131080
    }
}
