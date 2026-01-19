package com.andrerinas.headunitrevived.decoder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.PermissionChecker
import com.andrerinas.headunitrevived.utils.AppLog

class MicRecorder(private val micSampleRate: Int, private val context: Context) {

    private var audioRecord: AudioRecord? = null

    private val micBufferSize: Int
    private var micAudioBuf: ByteArray

    // Indicates whether mic recording is available on this device
    val isAvailable: Boolean

    init {
        val minSize = AudioRecord.getMinBufferSize(micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minSize <= 0) {
            // Device doesn't support the requested audio config (common on API 16)
            AppLog.w("MicRecorder: getMinBufferSize returned $minSize, mic recording unavailable")
            micBufferSize = 0
            micAudioBuf = ByteArray(0)
            isAvailable = false
        } else {
            micBufferSize = minSize
            micAudioBuf = ByteArray(minSize)
            isAvailable = true
        }
    }

    private var threadMicAudioActive = false
    private var threadMicAudio: Thread? = null
    var listener: Listener? = null

    interface Listener {
        fun onMicDataAvailable(mic_buf: ByteArray, mic_audio_len: Int)
    }

    fun stop() {
        AppLog.i("threadMicAudio: $threadMicAudio  threadMicAudioActive: $threadMicAudioActive")
        if (threadMicAudioActive) {
            threadMicAudioActive = false
            if (threadMicAudio != null) {
                threadMicAudio!!.interrupt()
            }
        }

        if (audioRecord != null) {
            audioRecord!!.stop()
            audioRecord!!.release()                                     // Release AudioTrack resources
            audioRecord = null
        }
    }

    private fun micAudioRead(aud_buf: ByteArray, max_len: Int): Int {
        var len = 0
        if (audioRecord == null) {
            return len
        }
        len = audioRecord!!.read(aud_buf, 0, max_len)
        if (len <= 0) {
            // If no audio data...
            if (len == AudioRecord.ERROR_INVALID_OPERATION)
            // -3
                AppLog.e("get expected interruption error due to shutdown: $len")
            return len
        }

        listener!!.onMicDataAvailable(aud_buf, len)
        return len
    }

    fun start(): Int {
        if (!isAvailable) {
            AppLog.w("MicRecorder: Cannot start, mic not available on this device")
            return -4
        }
        try {
            if (PermissionChecker.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                AppLog.e("No permission")
                audioRecord = null
                return -3
            }
            audioRecord = AudioRecord(MediaRecorder.AudioSource.DEFAULT, micSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, micBufferSize)
            audioRecord!!.startRecording()
            // Start input

            threadMicAudio = Thread(Runnable {
                while (threadMicAudioActive) {
                    micAudioRead(micAudioBuf, micBufferSize)
                }
            }, "mic_audio")

            threadMicAudioActive = true
            threadMicAudio!!.start()
            return 0
        } catch (e: Exception) {
            AppLog.e(e)
            audioRecord = null
            return -2
        }

    }

}
