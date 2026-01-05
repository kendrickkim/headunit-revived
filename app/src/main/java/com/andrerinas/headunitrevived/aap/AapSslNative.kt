package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.utils.AppLog
import javax.net.ssl.SSLEngineResult

/**
 * @author algavris
 * *
 * @date 20/10/2016.
 */

internal class AapSslNative : AapSsl {

    companion object {
        init {
            System.loadLibrary("crypto_1_1")
            System.loadLibrary("ssl_1_1")
            System.loadLibrary("hu_jni")
        }
    }

    private external fun native_ssl_prepare(): Int
    private external fun native_ssl_do_handshake(): Int
    private external fun native_ssl_bio_read(offset: Int, res_len: Int, res_buf: ByteArray): Int
    private external fun native_ssl_bio_write(offset: Int, msg_len: Int, msg_buf: ByteArray): Int
    private external fun native_ssl_read(offset: Int, res_len: Int, res_buf: ByteArray): Int
    private external fun native_ssl_write(offset: Int, msg_len: Int, msg_buf: ByteArray): Int

    private val bio_read = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val enc_buf = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val dec_buf = ByteArray(Messages.DEF_BUFFER_LENGTH)

    override fun prepare(): Int {
        val ret = native_ssl_prepare()
        if (ret < 0) {
            AppLog.e("SSL prepare failed: $ret")
        }
        return ret
    }

    override fun getHandshakeStatus(): SSLEngineResult.HandshakeStatus {
        return SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
    }

    override fun runDelegatedTasks() {
        // No-op for native implementation
    }

    override fun postHandshakeReset() {
        // No-op for native implementation, as it manages its own buffers
    }

    override fun handshakeRead(): ByteArray? {
        native_ssl_do_handshake()
        val size = native_ssl_bio_read(0, Messages.DEF_BUFFER_LENGTH, bio_read)
        if (size <= 0) {
            AppLog.i("SSL BIO read error")
            return null
        }
        val result = ByteArray(size)
        System.arraycopy(bio_read, 0, result, 0, size)
        return result
    }

    override fun handshakeWrite(start: Int, length: Int, buffer: ByteArray): Int {
        return native_ssl_bio_write(start, length, buffer)
    }

    override fun decrypt(start: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        val bytes_written = native_ssl_bio_write(start, length, buffer)
        if (bytes_written <= 0) {
            AppLog.e("BIO_write() bytes_written: %d", bytes_written)
            return null
        }

        val bytes_read = native_ssl_read(0, Messages.DEF_BUFFER_LENGTH, dec_buf)
        if (bytes_read <= 0) {
            AppLog.e("SSL_read bytes_read: %d", bytes_read)
            return null
        }

        return ByteArrayWithLimit(dec_buf, bytes_read)
    }

    override fun encrypt(offset: Int, length: Int, buffer: ByteArray): ByteArrayWithLimit? {
        val bytes_written = native_ssl_write(offset, length, buffer)
        if (bytes_written <= 0) {
            AppLog.e("SSL_write() bytes_written: %d", bytes_written)
            return null
        }

        if (bytes_written != length) {
            AppLog.e("SSL Write len: %d  bytes_written: %d", length, bytes_written)
        }

        AppLog.v("SSL Write len: %d  bytes_written: %d", length, bytes_written)

        val bytes_read = native_ssl_bio_read(offset, Messages.DEF_BUFFER_LENGTH - offset, enc_buf)
        if (bytes_read <= 0) {
            AppLog.e("BIO read  bytes_read: %d", bytes_read)
            return null
        }

        AppLog.v("BIO read bytes_read: %d", bytes_read)

        return ByteArrayWithLimit(enc_buf, bytes_read + offset)
    }

}
