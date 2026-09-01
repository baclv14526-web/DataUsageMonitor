package com.datamonitor.app

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service chạy nền, định kỳ kiểm tra lưu lượng data di động
 * đã dùng trong ngày và so sánh với hạn mức người dùng đặt.
 */
class DataUsageMonitorService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val CHECK_INTERVAL_MS = 60_000L // kiểm tra mỗi 60 giây
        const val ACTION_STOP = "com.datamonitor.app.STOP"
    }

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

        val initialNotif = NotificationHelper.buildOngoingNotification(this, 0L, Prefs.getDailyLimitMB(this))
        startForeground(NotificationHelper.NOTIF_ID_ONGOING, initialNotif)

        Prefs.setMonitoringEnabled(this, true)
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        job?.cancel()
        job = scope.launch {
            while (true) {
                checkUsageOnce()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun checkUsageOnce() {
        val limitMB = Prefs.getDailyLimitMB(this)
        val start = Prefs.startOfTodayMillis()
        val end = System.currentTimeMillis()

        val usedBytes = DataUsageUtils.getMobileDataUsageBytes(this, start, end)
        if (usedBytes < 0) return // chưa có quyền usage access

        val usedMB = DataUsageUtils.bytesToMB(usedBytes)
        val percent = if (limitMB > 0) ((usedMB / limitMB) * 100).toInt() else 0

        // Cập nhật thông báo nền liên tục
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(
            NotificationHelper.NOTIF_ID_ONGOING,
            NotificationHelper.buildOngoingNotification(this, usedBytes, limitMB)
        )

        when {
            percent >= 100 -> {
                if (!Prefs.wasNotifiedToday(this, 100)) {
                    NotificationHelper.sendCriticalNotification(this, usedBytes, limitMB)
                    Prefs.markNotifiedToday(this, 100)
                }
            }
            percent >= 80 -> {
                if (!Prefs.wasNotifiedToday(this, 80)) {
                    NotificationHelper.sendWarningNotification(this, percent, usedBytes, limitMB)
                    Prefs.markNotifiedToday(this, 80)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
