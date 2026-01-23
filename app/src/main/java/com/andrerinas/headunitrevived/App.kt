package com.andrerinas.headunitrevived

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.multidex.MultiDex
import com.andrerinas.headunitrevived.main.BackgroundNotification
import com.andrerinas.headunitrevived.ssl.ConscryptInitializer
import com.andrerinas.headunitrevived.utils.AppLog
import com.andrerinas.headunitrevived.utils.Settings
import java.io.File

class App : Application() {

    private val component: AppComponent by lazy {
        AppComponent(this)
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()

        if (ConscryptInitializer.isNeededForTls12()) {
            ConscryptInitializer.initialize()
        }

        val settings = Settings(this) // Create a Settings instance
        AppLog.init(settings) // Initialize AppLog with settings for conditional logging

        if (ConscryptInitializer.isAvailable()) {
            AppLog.i("Conscrypt security provider is active")
        } else if (ConscryptInitializer.isNeededForTls12()) {
            AppLog.w("Conscrypt not available - TLS 1.2 may not work on this device")
        }

        AppLog.d( "native library dir ${applicationInfo.nativeLibraryDir}")

        File(applicationInfo.nativeLibraryDir).listFiles()?.forEach { file ->
            AppLog.d( "   ${file.name}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            component.notificationManager.createNotificationChannel(NotificationChannel(defaultChannel, "Default", NotificationManager.IMPORTANCE_HIGH))
            val mediaChannel = NotificationChannel(BackgroundNotification.mediaChannel, "Media channel", NotificationManager.IMPORTANCE_DEFAULT)
            mediaChannel.setSound(null, null)
            component.notificationManager.createNotificationChannel(mediaChannel)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(AapBroadcastReceiver(), AapBroadcastReceiver.filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(AapBroadcastReceiver(), AapBroadcastReceiver.filter)
        }
    }

    companion object {
        const val defaultChannel = " default"

        fun get(context: Context): App {
            return context.applicationContext as App
        }
        fun provide(context: Context): AppComponent {
            return get(context).component
        }
    }
}
