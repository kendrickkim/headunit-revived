package com.andrerinas.headunitrevived.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.andrerinas.headunitrevived.utils.AppLog
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.pow

// Listener to notify about video dimension changes
interface VideoDimensionsListener {
    fun onVideoDimensionsChanged(width: Int, height: Int)
}

class VideoDecoder {
    private var mCodec: MediaCodec? = null
    private var mCodecBufferInfo: MediaCodec.BufferInfo? = null
    private var mInputBuffers: Array<ByteBuffer>? = null

    private var mHeight: Int = 0
    private var mWidth: Int = 0
    private var mSurface: Surface? = null
    private var mCodecConfigured: Boolean = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    var dimensionsListener: VideoDimensionsListener? = null

    val videoWidth: Int
        get() = mWidth

    val videoHeight: Int
        get() = mHeight

    fun decode(buffer: ByteArray, offset: Int, size: Int) {
        synchronized(sLock) {
            if (mCodec == null) {
                AppLog.v("Codec is not initialized")
                return
            }

            if (!mCodecConfigured) {
                var currentOffset = offset
                while (currentOffset < offset + size) {
                    val nalUnitSize = findNalUnitSize(buffer, currentOffset, offset + size)
                    if (nalUnitSize == -1) {
                        break // No more NAL units found
                    }

                    val nalUnitType = getNalType(buffer, currentOffset)
                    if (nalUnitType == 7) { // SPS
                        sps = buffer.copyOfRange(currentOffset, currentOffset + nalUnitSize)
                        AppLog.i("Got SPS sequence...")
                        // Try to parse dimensions from SPS
                        try {
                            val spsData = SpsParser.parse(sps!!)
                            if (spsData != null && (mWidth != spsData.width || mHeight != spsData.height)) {
                                AppLog.i("SPS parsed. Video dimensions: ${spsData.width}x${spsData.height}")
                                mWidth = spsData.width
                                mHeight = spsData.height
                                dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                            }
                        } catch (e: Exception) {
                            AppLog.e("Failed to parse SPS data", e)
                        }

                    } else if (nalUnitType == 8) { // PPS
                        pps = buffer.copyOfRange(currentOffset, currentOffset + nalUnitSize)
                        AppLog.i("Got PPS sequence...")
                    }
                    currentOffset += nalUnitSize
                }


                if (sps != null && pps != null) {
                    try {
                        configureDecoder(sps!!, pps!!)
                        mCodecConfigured = true
                    } catch (e: Exception) {
                        AppLog.e("Failed to configure decoder", e)
                    }
                }
                return
            }

            val content = ByteBuffer.wrap(buffer, offset, size)
            while (content.hasRemaining()) {
                if (!codec_input_provide(content)) {
                    AppLog.e("Dropping content because there are no available buffers.")
                    return
                }
                codecOutputConsume()
            }
        }
    }

    private fun codec_init() {
        synchronized(sLock) {
            try {
                mCodec = MediaCodec.createDecoderByType("video/avc")
            } catch (t: Throwable) {
                AppLog.e("Throwable creating video/avc decoder: $t")
            }
        }
    }

    @Throws(IOException::class)
    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // Now, mWidth and mHeight should be correctly set from SPS parsing
        if (mWidth == 0 || mHeight == 0) {
            AppLog.e("Cannot configure decoder, dimensions are zero.")
            return
        }
        val format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
        format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
        AppLog.i("VideoDecoder: configureDecoder with actual video width=$mWidth, height=$mHeight")
        try {
            mCodec!!.configure(format, mSurface, null, 0)
            mCodec!!.start()
            mInputBuffers = mCodec!!.inputBuffers
            mCodecBufferInfo = MediaCodec.BufferInfo()
            AppLog.i("Codec configured and started")
        } catch (e: Exception) {
            AppLog.e("Codec configuration failed", e)
            throw e
        }
    }

    private fun codec_stop(reason: String) {
        synchronized(sLock) {
            if (mCodec != null) {
                mCodec!!.stop()
            }
            mCodec = null
            mInputBuffers = null
            mCodecBufferInfo = null
            mCodecConfigured = false
            sps = null
            pps = null
            AppLog.i("Reason: $reason")
        }
    }

    private fun codec_input_provide(content: ByteBuffer): Boolean {
        try {
            val inputBufIndex = mCodec!!.dequeueInputBuffer(10000)
            if (inputBufIndex < 0) {
                AppLog.e("dequeueInputBuffer: $inputBufIndex")
                return false
            }

            val buffer = mInputBuffers!![inputBufIndex]
            buffer.clear()
            buffer.put(content)
            mCodec!!.queueInputBuffer(inputBufIndex, 0, buffer.limit(), 0, 0)
            return true
        } catch (t: Throwable) {
            AppLog.e(t)
        }
        return false
    }

    private fun codecOutputConsume() {
        var index: Int
        while (true) {
            index = mCodec!!.dequeueOutputBuffer(mCodecBufferInfo!!, 0)
            if (index >= 0) {
                mCodec!!.releaseOutputBuffer(index, true)
            } else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                AppLog.i("INFO_OUTPUT_BUFFERS_CHANGED")
                mInputBuffers = mCodec!!.inputBuffers
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outputFormat = mCodec!!.outputFormat
                AppLog.i("--- DECODER OUTPUT FORMAT CHANGED ---")
                AppLog.i("New video format: $outputFormat")
                // The format change might contain the definitive dimensions
                val newWidth = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                val newHeight = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                if (mWidth != newWidth || mHeight != newHeight) {
                    AppLog.i("Video dimensions changed via format. New: ${newWidth}x$newHeight")
                    mWidth = newWidth
                    mHeight = newHeight
                    dimensionsListener?.onVideoDimensionsChanged(mWidth, mHeight)
                }
            } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break
            } else {
                break
            }
        }
    }

    fun onSurfaceAvailable(surface: Surface) {
        synchronized(sLock) {
            if (mCodec != null) {
                AppLog.i("Codec is running")
                return
            }
        }
        mSurface = surface
        codec_init()
    }

    fun stop(reason: String) {
        codec_stop(reason)
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
        if (sps[0].toInt() == 0 && sps[1].toInt() == 0 && sps[2].toInt() == 1) {
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
