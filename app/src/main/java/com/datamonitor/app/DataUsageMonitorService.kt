package com.datamonitor.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service giám sát lưu lượng data di động.
 *
 * Crash cũ:
 *   - withContext(Dispatchers.Main) bên trong coroutine IO để notify:
 *     nếu service bị destroy trong lúc đang switch context → crash
 *   - scope không bị cancel khi service restart (START_STICKY tạo lại
 *     onStartCommand nhưng scope cũ vẫn chạy → 2 vòng lặp song song)
 *
 * Fix:
 *   - Dùng Dispatchers.Default cho coroutine (không block IO thread pool,
 *     không cần Main vì NotificationManager.notify() thread-safe)
 *   - Hủy job cũ trước khi tạo job mới trong onStartCommand
 *   - Guard isActive sau mỗi suspend call để thoát sạch khi cancel
 */
class DataUsageMonitorService : Service() {

    companion object {
        const val ACTION_STOP    = "com.datamonitor.app.STOP"
        const val CHECK_INTERVAL = 60_000L
        private const val TAG    = "DataMonitor"
    }

    // SupervisorJob: lỗi ở 1 coroutine con không cancel các coroutine khác
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + supervisor)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dừng service nếu nhận lệnh STOP
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "Nhận ACTION_STOP")
            Prefs.setMonitoringEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Đẩy foreground ngay lập tức — bắt buộc trong 5 giây sau startForegroundService()
        // Nếu trễ hơn → ANR / ForegroundServiceDidNotStartInTimeException (crash)
        startForeground(
            NotificationHelper.ID_ONGOING,
            NotificationHelper.buildOngoing(
                this, 0L, Prefs.getDailyLimitMB(this))
        )

        Prefs.setMonitoringEnabled(this, true)

        // Hủy job cũ nếu service bị restart (tránh 2 vòng lặp chạy song song)
        monitorJob?.cancel()
        monitorJob = scope.launch {
            // Dọn key cũ 1 lần khi start
            Prefs.pruneOldNotifKeys(this@DataUsageMonitorService)
            runMonitorLoop()
        }

        Log.d(TAG, "Service started, monitoring loop launched")
        return START_STICKY // Hệ thống tự restart nếu kill service
    }

    private suspend fun runMonitorLoop() {
        while (isActive) {
            try {
                checkAndNotify()
            } catch (e: Exception) {
                // Bắt mọi exception để vòng lặp không bị dừng do lỗi bất ngờ
                Log.e(TAG, "Lỗi trong checkAndNotify: ${e.message}", e)
            }
            delay(CHECK_INTERVAL)
        }
    }

    private fun checkAndNotify() {
        val limitMB   = Prefs.getDailyLimitMB(this)
        val startMs   = Prefs.startOfTodayMillis()
        val nowMs     = System.currentTimeMillis()
        val usedBytes = DataUsageUtils.getMobileDataUsageBytes(this, startMs, nowMs)

        if (usedBytes < 0) {
            // Chưa có quyền Usage Access — cập nhật notification nhắc nhở
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationHelper.ID_ONGOING,
                NotificationHelper.buildOngoing(this, 0L, limitMB))
            return
        }

        // Cập nhật notification nền (NotificationManager.notify thread-safe)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NotificationHelper.ID_ONGOING,
            NotificationHelper.buildOngoing(this, usedBytes, limitMB))

        val usedMB = DataUsageUtils.bytesToMB(usedBytes)
        val pct    = if (limitMB > 0) ((usedMB / limitMB) * 100).toInt().coerceIn(0, 999)
                     else 0

        Log.d(TAG, "Check: ${DataUsageUtils.formatBytes(usedBytes)} / $limitMB MB ($pct%)")

        when {
            pct >= 100 && !Prefs.wasNotifiedToday(this, 100) -> {
                NotificationHelper.sendCritical(this, usedBytes, limitMB)
                Prefs.markNotifiedToday(this, 100)
            }
            pct >= 80 && pct < 100 && !Prefs.wasNotifiedToday(this, 80) -> {
                NotificationHelper.sendWarning(this, pct, usedBytes, limitMB)
                Prefs.markNotifiedToday(this, 80)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        monitorJob?.cancel()
        supervisor.cancel()
        // Xóa notification nền khi service dừng hẳn
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NotificationHelper.ID_ONGOING)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
