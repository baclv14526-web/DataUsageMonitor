package com.datamonitor.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class DataUsageMonitorService : Service() {

    companion object {
        const val ACTION_STOP    = "com.datamonitor.app.ACTION_STOP"
        const val CHECK_INTERVAL = 60_000L  // 60 giây
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setMonitoringEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Khởi động foreground ngay lập tức với notification tạm
        startForeground(
            NotificationHelper.ID_ONGOING,
            NotificationHelper.buildOngoing(this, 0L, Prefs.getDailyLimitMB(this))
        )
        Prefs.setMonitoringEnabled(this, true)
        startMonitorLoop()
        return START_STICKY
    }

    private fun startMonitorLoop() {
        scope.launch {
            while (isActive) {
                checkAndNotify()
                delay(CHECK_INTERVAL)
            }
        }
    }

    private suspend fun checkAndNotify() {
        val limitMB   = Prefs.getDailyLimitMB(this)
        val startMs   = Prefs.startOfTodayMillis()
        val usedBytes = DataUsageUtils.getMobileDataUsageBytes(this, startMs, System.currentTimeMillis())

        if (usedBytes < 0) return  // Chưa có quyền Usage Access

        val usedMB = DataUsageUtils.bytesToMB(usedBytes)
        val pct    = if (limitMB > 0) ((usedMB / limitMB) * 100).toInt() else 0

        // Cập nhật notification nền
        withContext(Dispatchers.Main) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(
                NotificationHelper.ID_ONGOING,
                NotificationHelper.buildOngoing(this@DataUsageMonitorService, usedBytes, limitMB)
            )
        }

        when {
            pct >= 100 && !Prefs.wasNotifiedToday(this, 100) -> {
                NotificationHelper.sendCritical(this, usedBytes, limitMB)
                Prefs.markNotifiedToday(this, 100)
            }
            pct in 80..99 && !Prefs.wasNotifiedToday(this, 80) -> {
                NotificationHelper.sendWarning(this, pct, usedBytes, limitMB)
                Prefs.markNotifiedToday(this, 80)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
