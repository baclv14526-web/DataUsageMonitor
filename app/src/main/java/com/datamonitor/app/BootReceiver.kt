package com.datamonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (!Prefs.isMonitoringEnabled(ctx)) return
        val svc = Intent(ctx, DataUsageMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(svc)
        else
            ctx.startService(svc)
    }
}
