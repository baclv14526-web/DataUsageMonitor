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
    //
    // BUG ĐÃ SỬA: cache cũ chỉ kiểm tra "đã qua 60 giây chưa" mà KHÔNG
    // kiểm tra có sang ngày mới hay không. Kịch bản lỗi thực tế:
    //   - 23:59:50 → tính & cache "00:00 hôm nay" (tức 23:59:50 của
    //     ngày cũ tính theo mốc UTC/local)
    //   - 00:00:10 (chỉ mới 20 giây trôi qua, còn trong cửa sổ cache
    //     60 giây) → hàm trả về NHẦM mốc "00:00 hôm qua" thay vì
    //     "00:00 hôm nay"
    //   - Hậu quả: usedBytes được tính từ mốc SAI (dư ra gần 24 giờ dữ
    //     liệu của ngày hôm qua), có thể khiến app tưởng đã vượt hạn
    //     mức ngay đầu ngày mới và gửi cảnh báo giả
    //   - NGHIÊM TRỌNG HƠN: Prefs.markNotifiedToday(ctx, 100) sẽ đánh
    //     dấu "đã cảnh báo 100% hôm nay" dựa trên ngày THẬT (todayTag()
    //     không bị cache, luôn đúng) → cảnh báo giả này "dùng mất" lượt
    //     cảnh báo hợp lệ của cả ngày, khiến cảnh báo THẬT sau đó trong
    //     ngày (khi thực sự vượt hạn mức) KHÔNG BAO GIỜ được gửi nữa.
    //
    // FIX: lưu kèm "nhãn ngày" (cùng định dạng với todayTag()) tại thời
    // điểm cache. Chỉ dùng lại cache nếu vẫn cùng ngày — bất kể mốc thời
    // gian có nằm trong 60 giây hay không.
    @Volatile private var cachedTodayStart = 0L
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedDayTag: String? = null

    fun startOfTodayMillis(): Long {
        val now = System.currentTimeMillis()
        val currentDayTag = todayTag()

        if (now - cachedAt < 60_000L &&
            cachedTodayStart > 0L &&
            cachedDayTag == currentDayTag
        ) return cachedTodayStart

        val start = Calendar.getInstance().run {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        cachedTodayStart = start
        cachedAt = now
        cachedDayTag = currentDayTag
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
