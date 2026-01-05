package com.andrerinas.headunitrevived.aap

import com.andrerinas.headunitrevived.aap.protocol.Channel
import com.andrerinas.headunitrevived.connection.AccessoryConnection
import com.andrerinas.headunitrevived.utils.AppLog

/**
 * @author algavris
 * *
 * @date 13/02/2017.
 */

internal class AapReadSingleMessage(connection: AccessoryConnection, ssl: AapSsl, handler: AapMessageHandler)
    : AapRead.Base(connection, ssl, handler) {

    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    private val msgBuffer = ByteArray(65535) // unsigned short max

    override fun doRead(connection: AccessoryConnection): Int {
        // Step 1: Read the encrypted header
        val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, 5000, true) // Increased timeout
        if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) {
            AppLog.e("AapRead: Failed to read full header. Expected ${AapMessageIncoming.EncryptedHeader.SIZE}, got $headerSize. Disconnecting.")
            return -1
        }
        AppLog.d("AapRead: Received header ($headerSize bytes).")

        recvHeader.decode()
        AppLog.d("AapRead: Decoded header -> Channel: ${Channel.name(recvHeader.chan)}, Encrypted Length: ${recvHeader.enc_len}, Flags: ${recvHeader.flags}, Type: ${recvHeader.msg_type}")


        // This logic seems specific and might be part of a fragmentation protocol. Let's log it.
        if (recvHeader.flags == 0x09) {
            val sizeBuf = ByteArray(4)
            val readSize = connection.recvBlocking(sizeBuf, sizeBuf.size, 150, true)
            if(readSize != 4) {
                AppLog.e("AapRead: Failed to read fragment total size. Disconnecting.")
                return -1
            }
            val totalSize = Utils.bytesToInt(sizeBuf, 0, false)
            AppLog.d("AapRead: First fragment (flag 0x09) indicates total size: $totalSize")
        }

        // Step 2: Read the encrypted message body
        if (recvHeader.enc_len > msgBuffer.size) {
            AppLog.e("AapRead: Message too large (${recvHeader.enc_len} bytes). Buffer is only ${msgBuffer.size}. Disconnecting.")
            return -1
        }
        val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 5000, true) // Increased timeout
        if (msgSize != recvHeader.enc_len) {
            AppLog.e("AapRead: Failed to read full message body. Expected ${recvHeader.enc_len}, got $msgSize. Disconnecting.")
            return -1
        }
        AppLog.d("AapRead: Received message body ($msgSize bytes).")

        // Step 3: Decrypt the message
        try {
            AppLog.d("AapRead: Attempting to decrypt message...")
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)

            if (msg == null) {
                AppLog.e("AapRead: Decryption failed. enc_len: ${recvHeader.enc_len}, chan: ${Channel.name(recvHeader.chan)}, flags: ${recvHeader.flags}, msg_type: ${recvHeader.msg_type}. Disconnecting.")
                return -1
            }
            AppLog.i("AapRead: Decryption successful. Handling message for channel ${Channel.name(recvHeader.chan)}.")

            // Step 4: Handle the decrypted message
            handler.handle(msg)
            AppLog.d("AapRead: Message handled successfully.")
            return 0
        } catch (e: AapMessageHandler.HandleException) {
            AppLog.e("AapRead: Exception during message handling. Disconnecting.", e)
            return -1
        }
    }
}
