package com.andrerinas.headunitrevived.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.andrerinas.headunitrevived.App

import com.andrerinas.headunitrevived.location.GpsLocationService

/**
 * @author algavris
 * *
 * @date 18/12/2016.
 */
class BootCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            App.get(context).startService(GpsLocationService.intent(context))
        }, 10000)
    }
}
