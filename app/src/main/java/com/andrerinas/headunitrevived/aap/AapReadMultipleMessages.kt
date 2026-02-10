package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.aap.protocol.messages.Messages
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.utils.AppLog
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

internal class AapReadMultipleMessages(
        connection: AccessoryConnection,
        ssl: AapSsl,
        handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    private val fifo = ByteBuffer.allocate(Messages.DEF_BUFFER_LENGTH * 2)
    private val recvBuffer = ByteArray(Messages.DEF_BUFFER_LENGTH)
    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(65535) // unsigned short max

    override fun doRead(connection: AccessoryConnection): Int {
        val size = connection.recvBlocking(recvBuffer, recvBuffer.size, 150, false)
        if (size <= 0) {
            return 0
        }
        try {
            processBulk(size, recvBuffer)
        } catch (e: Exception) {
            AppLog.e("AapRead: Error in USB processBulk (ignored): ${e.message}")
            return 0 // Continue even on bulk error
        }
        return 0
    }

    private fun processBulk(size: Int, buf: ByteArray) {
        fifo.put(buf, 0, size)
        fifo.flip()

        while (fifo.remaining() >= AapMessageIncoming.EncryptedHeader.SIZE) {
            fifo.mark()
            fifo.get(recvHeader.buf, 0, recvHeader.buf.size)
            recvHeader.decode()

            if (recvHeader.flags == 0x09) {
                if (fifo.remaining() < 4) {
                    fifo.reset()
                    break
                }
                fifo.get(ByteArray(4), 0, 4) // Skip totalSize for now (Handled in AapReadSingle)
            }

            if (recvHeader.enc_len > msgBuffer.size) {
                AppLog.e("AapRead: Message too large (${recvHeader.enc_len} bytes). Skipping.")
                break // Cannot safely skip without knowing where next header is if this is corrupted
            }

            if (fifo.remaining() < recvHeader.enc_len) {
                fifo.reset()
                break
            }

            fifo.get(msgBuffer, 0, recvHeader.enc_len)

            try {
                val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

                if (msg == null) {
                    if (AppLog.LOG_VERBOSE) {
                        AppLog.d("AapRead: Decryption returned no message (likely SSL control packet). Continuing.")
                    }
                    continue 
                }

                handler.handle(msg)
            } catch (e: Exception) {
                AppLog.e("AapRead: Error handling USB message (ignored): ${e.message}")
                // Continue with next message in FIFO
            }
        }

        fifo.compact()
    }
}