package com.datamonitor.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Prefs {
    private const val FILE = "data_monitor_prefs"
    private const val KEY_LIMIT_MB      = "daily_limit_mb"
    private const val KEY_MONITORING    = "monitoring_enabled"
    private const val KEY_NOTIFIED      = "notified_"

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getDailyLimitMB(ctx: Context): Long =
        sp(ctx).getLong(KEY_LIMIT_MB, 1024L)

    fun setDailyLimitMB(ctx: Context, mb: Long) =
        sp(ctx).edit().putLong(KEY_LIMIT_MB, mb).apply()

    fun isMonitoringEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_MONITORING, false)

    fun setMonitoringEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_MONITORING, v).apply()

    private fun todayKey() =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun wasNotifiedToday(ctx: Context, pct: Int): Boolean =
        sp(ctx).getBoolean("${KEY_NOTIFIED}${pct}_${todayKey()}", false)

    fun markNotifiedToday(ctx: Context, pct: Int) =
        sp(ctx).edit().putBoolean("${KEY_NOTIFIED}${pct}_${todayKey()}", true).apply()

    fun startOfTodayMillis(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
