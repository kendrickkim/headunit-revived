package com.andrerinas.openheadunit.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.andrerinas.openheadunit.aap.AapService

class GpsLocationService : Service() {
    private var gpsLocation: GpsLocation? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (AapService.applicationQuitting) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (gpsLocation == null) {
            gpsLocation = GpsLocation(this)
        }

        gpsLocation?.start()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        gpsLocation?.stop()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, GpsLocationService::class.java)
        }
    }

}