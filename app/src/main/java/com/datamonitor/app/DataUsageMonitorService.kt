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
import kotlinx.coroutines.currentCoroutineContext
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
 *   - Hủy job cũ trước khi tạo job mới trong onStartCommand
 *   - Guard isActive sau mỗi suspend call để thoát sạch khi cancel
 *
 * BUG ĐÃ SỬA (lần review này) — sai loại Dispatcher:
 *   Code cũ dùng Dispatchers.Default cho vòng lặp giám sát. Nhưng
 *   Dispatchers.Default chỉ có số luồng CỐ ĐỊNH bằng số nhân CPU
 *   (thường 2-4 trên điện thoại), dành riêng cho tác vụ TÍNH TOÁN
 *   thuần túy (CPU-bound). checkAndNotify() bên trong lại gọi
 *   NetworkStatsManager.querySummary() — đây là lời gọi hệ thống
 *   CHẶN LUỒNG THẬT SỰ (blocking I/O/IPC), có thể mất vài chục đến
 *   vài trăm mili-giây, đặc biệt khi DataUsageUtils phải dò lại toàn
 *   bộ 6 chiến lược (SIM bị rút, cache bị invalidate...).
 *   Chiếm giữ 1 trong số ít luồng Default suốt thời gian đó có thể
 *   làm nghẽn các coroutine CPU-bound khác trong toàn bộ ứng dụng
 *   (kể cả coroutine của Activity khác nếu vô tình dùng chung pool
 *   mặc định). Dispatchers.IO tồn tại chính xác để giải quyết việc
 *   này — nó có pool lớn hơn nhiều (mặc định tới 64 luồng), được
 *   thiết kế riêng cho các lời gọi có thể chặn luồng dài hạn.
 */
class DataUsageMonitorService : Service() {

    companion object {
        const val ACTION_STOP    = "com.datamonitor.app.STOP"
        const val CHECK_INTERVAL = 60_000L
        private const val TAG    = "DataMonitor"
    }

    // SupervisorJob: lỗi ở 1 coroutine con không cancel các coroutine khác
    // Dispatchers.IO: đúng cho tác vụ có thể chặn luồng (NetworkStatsManager)
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + supervisor)
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
        // currentCoroutineContext().isActive: cách đúng để kiểm tra cancel
        // bên trong suspend fun (không phải CoroutineScope, nên 'isActive' bare
        // không resolve được receiver phù hợp)
        while (currentCoroutineContext().isActive) {
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
            // BUG ĐÃ SỬA: trước đây truyền 0L khiến buildOngoing() hiểu nhầm
            // là "đã đo được 0 byte" và hiện sai tiêu đề "Data hôm nay: 0 B"
            // thay vì "Cần cấp quyền". Phải truyền đúng -1L để buildOngoing()
            // nhận diện đúng trường hợp thiếu quyền.
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NotificationHelper.ID_ONGOING,
                NotificationHelper.buildOngoing(this, -1L, limitMB))
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
        // Xóa notification nền — gọi trực tiếp, không cần coroutine
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NotificationHelper.ID_ONGOING)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
