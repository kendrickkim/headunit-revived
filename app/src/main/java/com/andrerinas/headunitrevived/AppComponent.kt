package com.andrerinas.headunitrevived

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import com.andrerinas.headunitrevived.aap.AapTransport
import com.andrerinas.headunitrevived.decoder.AudioDecoder
import com.andrerinas.headunitrevived.decoder.VideoDecoder
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.utils.Settings

class AppComponent(private val app: App) {

    private var _transport: AapTransport? = null
    val transport: AapTransport
        get() {
            if (_transport == null) {
               _transport = AapTransport(audioDecoder, videoDecoder, audioManager, settings, backgroundNotification, app)
            }
            return _transport!!
        }

    val settings = Settings(app)
    val videoDecoder = VideoDecoder(settings)
    val audioDecoder = AudioDecoder()

    fun resetTransport() {
        _transport?.quit()
        _transport = null
    }

    private val backgroundNotification = BackgroundNotification(app)

    private val audioManager: AudioManager
        get() = app.getSystemService(Application.AUDIO_SERVICE) as AudioManager
    val notificationManager: NotificationManager
        get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val wifiManager: WifiManager
        get() = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
}
