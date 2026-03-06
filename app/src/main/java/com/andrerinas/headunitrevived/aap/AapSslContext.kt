package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.ssl.ConscryptInitializer
import com.andrerinas.headunitrevived.ssl.NoCheckTrustManager
import com.andrerinas.headunitrevived.ssl.SingleKeyKeyManager
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult

class AapSslContext(keyManager: SingleKeyKeyManager): AapSsl {
    private val sslContext: SSLContext = createSslContext(keyManager)
    private lateinit var sslEngine: SSLEngine
    private lateinit var txBuffer: ByteBuffer
    private lateinit var rxBuffer: ByteBuffer

    override fun performHandshake(connection: AccessoryConnection): Boolean {
        if (prepare() < 0) return false

        val buffer = ByteArray(Messages.DEF_BUFFER_LENGTH)

        while (getHandshakeStatus() != SSLEngineResult.HandshakeStatus.FINISHED &&
                getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {

            when (getHandshakeStatus()) {
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    val size = connection.recvBlocking(buffer, buffer.size, 5000, false)
                    if (size <= 6) {
                        AppLog.e("SSL Handshake: Receive failed or too small ($size)")

                        return false
                    }
                    handshakeWrite(6, size - 6, buffer)
                }

                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    // Wrap -> Send to connection
                    val handshakeData = handshakeRead()
                    val bio = Messages.createRawMessage(0, 3, 3, handshakeData)
                    if (connection.sendBlocking(bio, bio.size, 5000) < 0) {
                        AppLog.e("SSL Handshake: Send failed")
                        return false
                    }
                }

                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    runDelegatedTasks()
                }

                else -> {
                    AppLog.e("SSL Handshake: Unexpected status ${getHandshakeStatus()}")
                    return false
                }
            }
        }
        return true
    }

    override fun prepare(): Int {
        sslEngine = sslContext.createSSLEngine().apply {
            useClientMode = true
            session.also {
                val appBufferMax = it.applicationBufferSize
                val netBufferMax = it.packetBufferSize

                txBuffer = ByteBuffer.allocateDirect(netBufferMax)
                rxBuffer = ByteBuffer.allocateDirect(Messages.DEF_BUFFER_LENGTH.coerceAtLeast(appBufferMax + 50))
            }
        }
        sslEngine.beginHandshake()
        return 0
    }

    override fun postHandshakeReset() {
        // Clear buffers. In this implementation, the buffers are re-created for each wrap/unwrap
        // operation (implicitly by ByteBuffer.wrap), but clearing them ensures no stale data.
        txBuffer.clear()
        rxBuffer.clear()
    }

    override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return sslEngine.handshakeStatus
    }

    override fun runDelegatedTasks() {
        if (sslEngine.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = sslEngine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = sslEngine.delegatedTask
            }
            val hsStatus = sslEngine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    override fun handshakeRead(): ByteArray {
        txBuffer.clear()
        val result = sslEngine.wrap(emptyArray(), txBuffer)
        runDelegatedTasks(result, sslEngine)
        val resultBuffer = ByteArray(result.bytesProduced())
        txBuffer.flip()
        txBuffer.get(resultBuffer)
        return resultBuffer
    }

    override fun handshakeWrite(start: Int, length: Int, buffer: ByteArray): Int {
        rxBuffer.clear()
        val receivedHandshakeData = ByteArray(length)
        System.arraycopy(buffer, start, receivedHandshakeData, 0, length)

        val data = ByteBuffer.wrap(receivedHandshakeData)
        while (data.hasRemaining()) {
            val result = sslEngine.unwrap(data, rxBuffer)
            runDelegatedTasks(result, sslEngine)
        }
        return receivedHandshakeData.size
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::rxBuffer.isInitialized) {
                AppLog.w("SSL Decrypt: Not initialized yet")
                return null
            }
            try {
                rxBuffer.clear()
                val encrypted = ByteBuffer.wrap(buffer, start, length)
                val result = sslEngine.unwrap(encrypted, rxBuffer)
                runDelegatedTasks(result, sslEngine)
                
                if (AppLog.LOG_VERBOSE || result.bytesProduced() == 0) {
                    AppLog.d("SSL Decrypt Status: ${result.status}, Produced: ${result.bytesProduced()}, Consumed: ${result.bytesConsumed()}")
                }

                val resultBuffer = ByteArray(result.bytesProduced())
                rxBuffer.flip()
                rxBuffer.get(resultBuffer)
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Decrypt failed", e)
                return null
            }
        }
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        synchronized(this) {
            if (!::sslEngine.isInitialized || !::txBuffer.isInitialized) {
                AppLog.w("SSL Encrypt: Not initialized yet")
                return null
            }
            try {
                txBuffer.clear()
                val byteBuffer = ByteBuffer.wrap(buffer, offset, length)
                val result = sslEngine.wrap(byteBuffer, txBuffer)
                runDelegatedTasks(result, sslEngine)
                val resultBuffer = ByteArray(result.bytesProduced() + offset)
                txBuffer.flip()
                txBuffer.get(resultBuffer, offset, result.bytesProduced())
                return ByteArrayWithLimit(resultBuffer, resultBuffer.size)
            } catch (e: Exception) {
                AppLog.e("SSL Encrypt failed", e)
                return null
            }
        }
    }

    private fun runDelegatedTasks(result: SSLEngineResult, engine: SSLEngine) {
        if (result.handshakeStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
            var runnable: Runnable? = engine.delegatedTask
            while (runnable != null) {
                runnable.run()
                runnable = engine.delegatedTask
            }
            val hsStatus = engine.handshakeStatus
            if (hsStatus === SSLEngineResult.HandshakeStatus.NEED_TASK) {
                throw Exception("handshake shouldn't need additional tasks")
            }
        }
    }

    companion object {
        private fun createSslContext(keyManager: SingleKeyKeyManager): SSLContext {
            val providerName = ConscryptInitializer.getProviderName()

            val sslContext = if (providerName != null) {
                try {
                    AppLog.d("Creating SSLContext with Conscrypt provider")
                    SSLContext.getInstance("TLS", providerName)
                } catch (e: Exception) {
                    AppLog.w("Failed to create SSLContext with Conscrypt, using default", e)
                    SSLContext.getInstance("TLS")
                }
            } else {
                AppLog.d("Creating SSLContext with default provider")
                SSLContext.getInstance("TLS")
            }

            return sslContext.apply {
                init(arrayOf(keyManager), arrayOf(NoCheckTrustManager()), null)
                // Disable session caching to prevent stale states across connection attempts
                clientSessionContext.sessionCacheSize = 0
                clientSessionContext.sessionTimeout = 1
            }
        }
    }
}
