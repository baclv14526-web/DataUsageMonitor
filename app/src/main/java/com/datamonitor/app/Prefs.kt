package com.datamonitor.app

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * Quản lý toàn bộ cài đặt người dùng qua SharedPreferences.
 *
 * Tối ưu:
 * - Cache SharedPreferences instance trong application context để tránh
 *   tạo lại mỗi lần gọi (SharedPreferences.getSharedPreferences nội bộ
 *   có lock, gọi nhiều lần trên main thread gây jank nhỏ)
 * - Dùng Calendar.getInstance() tái sử dụng thay vì SimpleDateFormat
 *   tạo mới mỗi lần (SimpleDateFormat không thread-safe và nặng hơn)
 * - startOfTodayMillis() cache kết quả trong cùng phút để tránh
 *   tính lại nhiều lần trong 1 chu kỳ check 60 giây
 */
object Prefs {
    private const val FILE          = "dm_prefs"
    private const val KEY_LIMIT_MB  = "daily_limit_mb"
    private const val KEY_ENABLED   = "monitoring_on"
    private const val KEY_NOTIFIED  = "notif_"

    // Cache SP instance — dùng applicationContext tránh memory leak
    @Volatile private var sp: SharedPreferences? = null

    private fun sp(ctx: Context): SharedPreferences =
        sp ?: ctx.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .also { sp = it }

    // ── Hạn mức ──────────────────────────────────────────────────
    fun getDailyLimitMB(ctx: Context): Long =
        sp(ctx).getLong(KEY_LIMIT_MB, 1024L)

    fun setDailyLimitMB(ctx: Context, mb: Long) =
        sp(ctx).edit().putLong(KEY_LIMIT_MB, mb).apply()

    // ── Trạng thái giám sát ──────────────────────────────────────
    fun isMonitoringEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(KEY_ENABLED, false)

    fun setMonitoringEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    // ── Cảnh báo theo ngày ───────────────────────────────────────
    // Key = "notif_80_20240615" — tự hết hạn sang ngày mới
    private fun todayTag(): String {
        val c = Calendar.getInstance()
        return "%d%02d%02d".format(c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun wasNotifiedToday(ctx: Context, pct: Int): Boolean =
        sp(ctx).getBoolean("$KEY_NOTIFIED${pct}_${todayTag()}", false)

    fun markNotifiedToday(ctx: Context, pct: Int) =
        sp(ctx).edit().putBoolean("$KEY_NOTIFIED${pct}_${todayTag()}", true).apply()

    // ── Thời gian đầu ngày ───────────────────────────────────────
    // Cache trong 60 giây — đủ cho 1 chu kỳ check, tránh tính lại Calendar
    @Volatile private var cachedTodayStart = 0L
    @Volatile private var cachedAt = 0L

    fun startOfTodayMillis(): Long {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 60_000L && cachedTodayStart > 0L)
            return cachedTodayStart
        val start = Calendar.getInstance().run {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        cachedTodayStart = start
        cachedAt = now
        return start
    }

    // ── Dọn dẹp key cũ (gọi 1 lần/ngày khi service start) ───────
    // Xóa các key "notif_*" của ngày hôm qua trở về trước để tránh
    // SharedPreferences phình to theo thời gian
    fun pruneOldNotifKeys(ctx: Context) {
        val today = todayTag()
        val editor = sp(ctx).edit()
        sp(ctx).all.keys
            .filter { it.startsWith(KEY_NOTIFIED) && !it.endsWith(today) }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}
