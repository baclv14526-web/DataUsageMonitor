package com.datamonitor.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("DataMonitor", "BootReceiver: $action")

        // Chỉ xử lý 2 action đã đăng ký trong manifest
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (!Prefs.isMonitoringEnabled(ctx)) return

        val svc = Intent(ctx, DataUsageMonitorService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(svc)
            else
                ctx.startService(svc)
        } catch (e: Exception) {
            Log.e("DataMonitor", "BootReceiver: không thể start service: ${e.message}")
        }
    }
}
