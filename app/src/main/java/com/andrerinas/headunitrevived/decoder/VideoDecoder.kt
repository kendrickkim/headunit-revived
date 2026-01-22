package com.andrerinas.headunitrevived.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

// Listener to notify about video dimension changes
interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

class VideoDecoder(private val settings: Settings) {
    private var mCodec: MediaCodec? = null
    private var mCodecBufferInfo: MediaCodec.BufferInfo? = null
    // Only used for synchronous mode (API < 21 or forced legacy)
    private var mInputBuffers: Array<ByteBuffer>? = null

    private var mHeight: Int = 0
    private var mWidth: Int = 0
    private var mSurface: Surface? = null
    private var mCodecConfigured: Boolean = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var timeoutCounter = 0

    // For asynchronous decoding (API >= 21)
    private val freeInputBuffers: BlockingQueue<Int> = ArrayBlockingQueue(1024)
    private var callbackThread: HandlerThread? = null

    var dimensionsListener: VideoDimensionsListener? = null
    var onFirstFrameListener: (() -> Unit)? = null

    val videoWidth: Int
        get() = mWidth

    val videoHeight: Int
        get() = mHeight

    // Callback only used on API 21+ and if legacy mode is NOT forced
    private val mCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                freeInputBuffers.offer(index)
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    codec.releaseOutputBuffer(index, true)
                } catch (e: Exception) {
                    AppLog.e("Error releasing output buffer: ${e.message}")
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                AppLog.e("MediaCodec error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                handleOutputFormatChange(format)
            }
        }
    } else null

    private fun handleOutputFormatChange(format: MediaFormat) {
        AppLog.i("--- DECODER OUTPUT FORMAT CHANGED ---")
        AppLog.i("New video format: $format")
        val newWidth = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { mWidth }
        val newHeight = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { mHeight }
        if (mWidth != newWidth || mHeight != newHeight) {
            AppLog.i("Video dimensions changed via format. New: ${newWidth}x$newHeight")
            mWidth = newWidth
            mHeight = newHeight
            dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
        }
        try {
            mCodec?.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        } catch (e: Exception) {
            // Ignore if scaling mode not supported on very old devices
        }
    }

        fun decode(buffer: ByteArray, offset: Int, size: Int, forceSoftware: Boolean, codecName: String) {
            synchronized(sLock) {
                // 1. Detect actual codec type from stream header
                val detectedType = Companion.detectCodecType(buffer, offset, size)
    
                // 2. If codec is running, check if it matches the detected type
                if (mCodec != null && detectedType != null) {
                    val isCurrentHevc = mCodec!!.name.lowercase(Locale.ROOT).contains("hevc") ||
                            mCodec!!.name.lowercase(Locale.ROOT).contains("mtk") && codecName.contains("H.265") // Fallback check
                    val isNewHevc = (detectedType == CodecType.H265)
    
                    // Only restart if we are sure there is a mismatch
                    if (isCurrentHevc != isNewHevc) {
                        AppLog.w("VideoDecoder: Codec mismatch detected! Restarting.")
                        codec_stop("Codec mismatch")
                    }
                }

            // 3. Scan for SPS/PPS if not configured (to catch them even if surface is not ready)
            if (!mCodecConfigured) {
                var currentOffset = offset
                while (currentOffset < offset + size) {
                    val nalUnitSize = findNalUnitSize(buffer, currentOffset, offset + size)
                    if (nalUnitSize == -1) break

                    val nalUnitType = getNalType(buffer, currentOffset)
                    if (nalUnitType == 7) { // SPS
                        sps = buffer.copyOfRange(currentOffset, currentOffset + nalUnitSize)
                        AppLog.i("Got SPS sequence...")
                        try {
                            val spsData = SpsParser.parse(sps!!)
                            if (spsData != null && (mWidth != spsData.width || mHeight != spsData.height)) {
                                AppLog.i("SPS parsed. Video dimensions: ${spsData.width}x${spsData.height}")
                                mWidth = spsData.width
                                mHeight = spsData.height
                                dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                            }
                        } catch (e: Exception) { AppLog.e("Failed to parse SPS", e) }
                    } else if (nalUnitType == 8) { // PPS
                        pps = buffer.copyOfRange(currentOffset, currentOffset + nalUnitSize)
                        AppLog.i("Got PPS sequence...")
                    }
                    currentOffset += nalUnitSize
                }
            }

            // 4. Initialize if not running (or stopped above)
            if (mCodec == null) {
                if (mSurface == null || !mSurface!!.isValid) {
                    return
                }

                val finalCodecName = detectedType?.name ?: codecName
                AppLog.i("VideoDecoder: Init codec for $finalCodecName (ForceSW: $forceSoftware)")
                    val mime = if (finalCodecName == "H265" || finalCodecName == "H.265") "video/hevc" else "video/avc"
                    codec_init(mime, forceSoftware)
                }
    
                if (mCodec == null) {
                    return
                }
    
                if (!mCodecConfigured) {
                    val isH265 = codecName.contains("H265") || codecName.contains("H.265") || (mCodec?.name?.lowercase(Locale.ROOT)?.contains("hevc") == true)
    
                    if (!isH265) {
                        if (sps != null && pps != null) {
                            try {
                                if (!configureDecoder("video/avc")) return
                                mCodecConfigured = true
                            } catch (e: Exception) {
                                AppLog.e("Failed to configure decoder", e)
                                codec_stop("Configuration failed")
                                return
                            }
                        }
                        // For H264, if configured (or not), we return here if we processed config data.
                        // If not configured (no SPS/PPS), we return to wait for more data.
                        return
                    } else {
                        // H265 path (legacy behavior, or if we want to support it without SPS parsing)
                        try {
                            if (!configureDecoder("video/hevc")) return
                            mCodecConfigured = true
                        } catch (e: Exception) {
                            AppLog.e("Failed to configure decoder", e)
                            codec_stop("Configuration failed")
                            return
                        }
                    }
                }
    
                val presentationTimeUs = System.nanoTime() / 1000
    
                val content = ByteBuffer.wrap(buffer, offset, size)
                while (content.hasRemaining()) {
                    if (!codec_input_provide(content, presentationTimeUs)) {
                        return
                    }
    
                    // For synchronous mode (API < 21 or forced legacy), we must manually drain output
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || settings.forceLegacyDecoder) {
                        codecOutputConsumeSync()
                    }
                }
            }
        }
    private fun codec_init(mime: String, forceSoftware: Boolean) {
        synchronized(sLock) {
            try {
                val codecNameToUse = findBestCodec(mime, !forceSoftware)
                if (codecNameToUse == null) {
                    AppLog.e("No suitable decoder found for mime type $mime, forceSoftware: $forceSoftware")
                    return
                }
                AppLog.i("Selected decoder: $codecNameToUse for $mime (forceSoftware: $forceSoftware)")
                mCodec = MediaCodec.createByCodecName(codecNameToUse)
            } catch (t: Throwable) {
                AppLog.e("Throwable creating decoder for $mime: $t")
            }
        }
    }

    private fun configureDecoder(mime: String): Boolean {
        // Robust check: if surface is null or invalid, don't try to configure.
        // This prevents crashes on devices where surface release happens quickly.
        if (mSurface == null || !mSurface!!.isValid) {
            AppLog.w("Surface is not valid, skipping configuration")
            return false
        }

        val width = if (mWidth > 0) mWidth else 1920
        val height = if (mHeight > 0) mHeight else 1080

        val format = MediaFormat.createVideoFormat(mime, width, height)
        if (sps != null) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        }
        if (pps != null) {
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        }

        val isLegacy = settings.forceLegacyDecoder || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val maxInputSize = if (isLegacy) 2097152 else 10485760
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInputSize)
        }
        if (!isLegacy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            format.setFloat(MediaFormat.KEY_OPERATING_RATE, 120.0f)
        }

        AppLog.i("VideoDecoder: configureDecoder with mime=$mime, target dimensions=${width}x${height} (Legacy: $isLegacy)")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !settings.forceLegacyDecoder) {
                if (callbackThread == null || !callbackThread!!.isAlive) {
                    callbackThread = HandlerThread("VideoDecoderCallbackThread")
                    callbackThread!!.start()
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handler = Handler(callbackThread!!.looper)
                    mCodec!!.setCallback(mCallback!!, handler)
                } else {
                    mCodec!!.setCallback(mCallback!!)
                }
            }

            mCodec!!.configure(format, mSurface, null, 0)
            mCodec!!.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            mCodec!!.start()

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP || settings.forceLegacyDecoder) {
                mInputBuffers = mCodec!!.inputBuffers
                mCodecBufferInfo = MediaCodec.BufferInfo()
            } else {
                mCodecBufferInfo = MediaCodec.BufferInfo()
            }

            AppLog.i("Codec configured and started. Selected codec: ${mCodec?.name}")
            return true
        } catch (e: Exception) {
            AppLog.e("Codec configuration failed", e)
            throw e
        }
    }

    private fun codec_stop(reason: String) {
        synchronized(sLock) {
            if (mCodec != null) {
                try {
                    mCodec!!.stop()
                    mCodec!!.release()
                } catch (e: Exception) {
                    AppLog.e("Error during codec release: ${e.message}")
                }
            }
            mCodec = null
            mCodecBufferInfo = null
            mCodecConfigured = false
            freeInputBuffers.clear()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                callbackThread?.quitSafely()
            } else {
                callbackThread?.quit()
            }
            callbackThread = null

            AppLog.i("Reason: $reason")
        }
    }

    private fun codec_input_provide(content: ByteBuffer, presentationTimeUs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && !settings.forceLegacyDecoder) {
            // Asynchronous Mode (API 21+)
            try {
                val inputBufIndex = freeInputBuffers.poll(100, TimeUnit.MILLISECONDS) ?: -1
                if (inputBufIndex >= 0) {
                    timeoutCounter = 0
                    val buffer = mCodec!!.getInputBuffer(inputBufIndex) ?: return false
                    buffer.clear()
                    try {
                        buffer.put(content)
                    } catch (e: java.nio.BufferOverflowException) {
                        AppLog.e("Input buffer overflow (Async)! Cap: ${buffer.capacity()}, Req: ${content.remaining()}")
                        return false
                    }
                    mCodec!!.queueInputBuffer(inputBufIndex, 0, buffer.limit(), presentationTimeUs, 0)
                    return true
                } else {
                    AppLog.e("dequeueInputBuffer timed out (queue empty). Frame will be dropped.")
                    timeoutCounter++
                    if (timeoutCounter > 20) {
                        AppLog.w("Too many timeouts! Restarting codec...")
                        codec_stop("Timeout limit reached")
                        timeoutCounter = 0
                    }
                    return false
                }
            } catch (t: Throwable) {
                AppLog.e("Error providing codec input (Async)", t)
                return false
            }
        } else {
            // Synchronous Mode (API < 21 or forced legacy)
            try {
                val inputBufIndex = mCodec!!.dequeueInputBuffer(10000)
                if (inputBufIndex >= 0) {
                    val buffer = mInputBuffers!![inputBufIndex] ?: return false
                    buffer.clear()
                    try {
                        buffer.put(content)
                    } catch (e: java.nio.BufferOverflowException) {
                        AppLog.e("Input buffer overflow (Sync)! Cap: ${buffer.capacity()}, Req: ${content.remaining()}")
                        return false
                    }
                    mCodec!!.queueInputBuffer(inputBufIndex, 0, buffer.limit(), presentationTimeUs, 0)
                    return true
                } else {
                    // Try to drain output to free up input
                    codecOutputConsumeSync()
                    return false
                }
            } catch (t: Throwable) {
                AppLog.e("Error providing codec input (Sync)", t)
                return false
            }
        }
    }

    private fun codecOutputConsumeSync() {
        var index: Int
        while (true) {
            index = try {
                mCodec!!.dequeueOutputBuffer(mCodecBufferInfo!!, 0)
            } catch (e: Exception) {
                -1
            }

            if (index >= 0) {
                mCodec!!.releaseOutputBuffer(index, true)
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                mInputBuffers = mCodec!!.inputBuffers
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleOutputFormatChange(mCodec!!.outputFormat)
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else {
                break
            }
        }
    }

    fun setSurface(surface: Surface?) {
        synchronized(sLock) {
            val oldHash = mSurface?.hashCode() ?: 0
            val newHash = surface?.hashCode() ?: 0
            AppLog.i("VideoDecoder.setSurface | Old: $oldHash, New: $newHash")

            if (mSurface === surface) {
                // Surface object identical, skipping restart to prevent crashes on sensitive devices (Mediatek)
                // Try to update scaling mode just in case
                if (mCodec != null) {
                    try {
                        mCodec!!.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                    } catch (e: Exception) {}
                }
                return
            }
            if (mCodec != null) {
                AppLog.i("Codec is running, stopping for new surface")
                codec_stop("New surface")
            }
            mSurface = surface

            if (mSurface != null) {
                val mime = when (settings.videoCodec) {
                    "H.265" -> "video/hevc"
                    "Auto" -> if (Companion.isHevcSupported()) "video/hevc" else "video/avc"
                    else -> "video/avc"
                }
                AppLog.i("VideoDecoder.setSurface | Pre-initializing codec: $mime")
                codec_init(mime, settings.forceSoftwareDecoding)
            }
        }
    }

    fun stop(reason: String) {
        codec_stop(reason)
    }

    enum class CodecType(val mimeType: String, val displayName: String) {
        H264("video/avc", "H.264/AVC"),
        H265("video/hevc", "H.265/HEVC");

        companion object {
            fun fromName(name: String): CodecType {
                return when (name) {
                    "H.265" -> H265
                    else -> H264
                }
            }
        }
    }

    companion object {
        private val sLock = Object()

        private fun findNalUnitSize(buffer: ByteArray, offset: Int, limit: Int): Int {
            var i = offset + 4 // Start after the 0x00 00 00 01 start code
            while (i < limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0 && buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) {
                    return i - offset
                }
                i++
            }
            return limit - offset // Last NAL unit
        }

        private fun getNalType(ba: ByteArray, offset: Int): Int {
            // NAL unit type is in the byte after the start code (0x00 00 00 01)
            // The NAL unit type is the last 5 bits of that byte
            return ba[offset + 4].toInt() and 0x1f
        }

        fun detectCodecType(buffer: ByteArray, offset: Int, size: Int): CodecType? {
            var i = offset
            val limit = offset + size - 5
            while (i < limit) {
                if (buffer[i] == 0.toByte() && buffer[i+1] == 0.toByte() && buffer[i+2] == 0.toByte() && buffer[i+3] == 1.toByte()) {
                    val header = buffer[i+4]
                    if ((header.toInt() and 0x1F) == 7) return CodecType.H264
                    if (((header.toInt() shr 1) and 0x3F) == 33) return CodecType.H265
                }
                i++
            }
            return null
        }

        fun isHevcSupported(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                for (codecInfo in codecList.codecInfos) {
                    if (codecInfo.isEncoder) continue
                    if (codecInfo.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }) {
                        if (isHardwareAccelerated(codecInfo)) {
                            return true
                        }
                    }
                }
                return false
            }
            return false
        }

        fun findBestCodec(mimeType: String, preferHardware: Boolean): String? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
                var hardwareCodec: String? = null
                var softwareCodec: String? = null

                for (codecInfo in codecList.codecInfos) {
                    if (codecInfo.isEncoder) continue
                    if (codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                        if (isHardwareAccelerated(codecInfo)) {
                            if (hardwareCodec == null) hardwareCodec = codecInfo.name
                        } else {
                            if (softwareCodec == null) softwareCodec = codecInfo.name
                        }
                    }
                }

                if (preferHardware && hardwareCodec != null) {
                    AppLog.i("Selected hardware decoder: $hardwareCodec for $mimeType")
                    return hardwareCodec
                }
                if (softwareCodec != null) {
                    AppLog.i("Selected software decoder: $softwareCodec for $mimeType")
                    return softwareCodec
                }
                if (hardwareCodec != null) {
                    AppLog.i("Selected hardware decoder as fallback: $hardwareCodec for $mimeType")
                    return hardwareCodec
                }
                return null
            } else {
                // API < 21 Implementation
                var hardwareCodec: String? = null
                var softwareCodec: String? = null
                val count = MediaCodecList.getCodecCount()
                for (i in 0 until count) {
                    val codecInfo = MediaCodecList.getCodecInfoAt(i)
                    if (codecInfo.isEncoder) continue
                    if (codecInfo.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                        if (isHardwareAccelerated(codecInfo)) {
                            if (hardwareCodec == null) hardwareCodec = codecInfo.name
                        } else {
                            if (softwareCodec == null) softwareCodec = codecInfo.name
                        }
                    }
                }

                if (preferHardware && hardwareCodec != null) {
                    AppLog.i("Selected hardware decoder: $hardwareCodec for $mimeType")
                    return hardwareCodec
                }
                if (softwareCodec != null) {
                    AppLog.i("Selected software decoder: $softwareCodec for $mimeType")
                    return softwareCodec
                }
                if (hardwareCodec != null) {
                    AppLog.i("Selected hardware decoder as fallback: $hardwareCodec for $mimeType")
                    return hardwareCodec
                }
                return null
            }
        }

        private fun isHardwareAccelerated(codecInfo: MediaCodecInfo): Boolean {
            val name = codecInfo.name.lowercase(Locale.ROOT)

            // Blacklist known broken MTK HEVC decoder to prevent crashes in Auto mode
            if (name.contains("mtk") && name.contains("hevc")) {
                return false
            }

            return !name.startsWith("omx.google.") &&
                    !name.startsWith("c2.android.") &&
                    !name.contains(".sw.")
        }
    }
}

// Helper class for reading bits from a byte array
private class BitReader(private val buffer: ByteArray) {
    private var bitPosition = 0

    fun readBit(): Int {
        val byteIndex = bitPosition / 8
        val bitIndex = 7 - (bitPosition % 8)
        bitPosition++
        return (buffer[byteIndex].toInt() shr bitIndex) and 1
    }

    fun readBits(count: Int): Int {
        var result = 0
        for (i in 0 until count) {
            result = (result shl 1) or readBit()
        }
        return result
    }

    // Reads unsigned exponential-golomb coded integer
    fun readUE(): Int {
        var leadingZeroBits = 0
        while (readBit() == 0) {
            leadingZeroBits++
        }
        if (leadingZeroBits == 0) {
            return 0
        }
        val codeNum = (2.0.pow(leadingZeroBits.toDouble()) - 1 + readBits(leadingZeroBits)).toInt()
        return codeNum
    }
}

data class SpsData(val width: Int, val height: Int)

private object SpsParser {
    fun parse(sps: ByteArray): SpsData? {
        // We need to skip the NAL unit header (e.g., 00 00 00 01 67 ...)
        // Let's find the start of the SPS payload
        var payloadIndex = 4 // Default for 00 00 00 01
        if (sps.size > 2 && sps[0].toInt() == 0 && sps[1].toInt() == 0 && sps[2].toInt() == 1) {
            payloadIndex = 3
        }

        // We only need to parse up to the dimensions, no need for a full SPS parser
        try {
            val reader = BitReader(sps.copyOfRange(payloadIndex, sps.size))
            reader.readBits(8) // NAL unit type, already know it's 7, but read it from payload
            val profileIdc = reader.readBits(8)
            reader.readBits(16) // flags and level_idc
            reader.readUE() // seq_parameter_set_id

            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244 || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118 || profileIdc == 128) {
                val chromaFormatIdc = reader.readUE()
                if (chromaFormatIdc == 3) {
                    reader.readBit() // separate_colour_plane_flag
                }
                reader.readUE() // bit_depth_luma_minus8
                reader.readUE() // bit_depth_chroma_minus8
                reader.readBit() // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresentFlag = reader.readBit()
                if (seqScalingMatrixPresentFlag == 1) {
                    for (i in 0 until if (chromaFormatIdc != 3) 8 else 12) {
                        val seqScalingListPresentFlag = reader.readBit()
                        if (seqScalingListPresentFlag == 1) {
                            // Skip scaling list data
                            var lastScale = 8
                            var nextScale = 8
                            val sizeOfScalingList = if (i < 6) 16 else 64
                            for (j in 0 until sizeOfScalingList) {
                                if (nextScale != 0) {
                                    val deltaScale = reader.readUE() // Can be signed, but we just skip
                                    nextScale = (lastScale + deltaScale + 256) % 256
                                }
                                if (nextScale != 0) {
                                    lastScale = nextScale
                                }
                            }
                        }
                    }
                }
            }

            reader.readUE() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUE()
            if (picOrderCntType == 0) {
                reader.readUE() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBit() // delta_pic_order_always_zero_flag
                reader.readUE() // offset_for_non_ref_pic (signed)
                reader.readUE() // offset_for_top_to_bottom_field (signed)
                val numRefFramesInPicOrderCntCycle = reader.readUE()
                for (i in 0 until numRefFramesInPicOrderCntCycle) {
                    reader.readUE() // offset_for_ref_frame (signed)
                }
            }

            reader.readUE() // max_num_ref_frames
            reader.readBit() // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUE()
            val picHeightInMapUnitsMinus1 = reader.readUE()
            val frameMbsOnlyFlag = reader.readBit()

            val width = (picWidthInMbsMinus1 + 1) * 16
            var height = (2 - frameMbsOnlyFlag) * (picHeightInMapUnitsMinus1 + 1) * 16

            if (frameMbsOnlyFlag == 0) {
                reader.readBit() // mb_adaptive_frame_field_flag
            }
            reader.readBit() // direct_8x8_inference_flag

            var frameCropLeftOffset = 0
            var frameCropRightOffset = 0
            var frameCropTopOffset = 0
            var frameCropBottomOffset = 0

            val frameCroppingFlag = reader.readBit()
            if (frameCroppingFlag == 1) {
                frameCropLeftOffset = reader.readUE()
                frameCropRightOffset = reader.readUE()
                frameCropTopOffset = reader.readUE()
                frameCropBottomOffset = reader.readUE()
            }

            val finalWidth = width - (frameCropLeftOffset * 2) - (frameCropRightOffset * 2)
            val finalHeight = height - (frameCropTopOffset * 2) - (frameCropBottomOffset * 2)

            return SpsData(finalWidth, finalHeight)
        } catch (e: Exception) {
            AppLog.e("SPS parsing failed: ${e.message}")
            return null
        }
    }
}