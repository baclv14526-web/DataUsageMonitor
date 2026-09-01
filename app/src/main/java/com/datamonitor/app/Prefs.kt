package com.datamonitor.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lớp quản lý cấu hình lưu trong SharedPreferences:
 * - Hạn mức data hàng ngày (MB)
 * - Trạng thái bật/tắt giám sát
 * - Đánh dấu đã cảnh báo ở ngưỡng nào trong ngày (tránh spam thông báo)
 */
object Prefs {
    private const val FILE = "data_monitor_prefs"
    private const val KEY_LIMIT_MB = "daily_limit_mb"
    private const val KEY_MONITORING = "monitoring_enabled"
    private const val KEY_NOTIFIED_PREFIX = "notified_"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getDailyLimitMB(context: Context): Long =
        sp(context).getLong(KEY_LIMIT_MB, 1024L) // mặc định 1GB/ngày

    fun setDailyLimitMB(context: Context, mb: Long) {
        sp(context).edit().putLong(KEY_LIMIT_MB, mb).apply()
    }

    fun isMonitoringEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_MONITORING, false)

    fun setMonitoringEnabled(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_MONITORING, enabled).apply()
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Kiểm tra xem ngưỡng (vd 80, 100) đã được cảnh báo trong hôm nay chưa */
    fun wasNotifiedToday(context: Context, thresholdPercent: Int): Boolean {
        val key = "$KEY_NOTIFIED_PREFIX${thresholdPercent}_${todayKey()}"
        return sp(context).getBoolean(key, false)
    }

    fun markNotifiedToday(context: Context, thresholdPercent: Int) {
        val key = "$KEY_NOTIFIED_PREFIX${thresholdPercent}_${todayKey()}"
        sp(context).edit().putBoolean(key, true).apply()
    }

    /** Mốc đầu ngày (00:00:00) hôm nay, tính bằng millis */
    fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
