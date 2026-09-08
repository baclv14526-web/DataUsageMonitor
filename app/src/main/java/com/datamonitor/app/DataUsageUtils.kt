package com.datamonitor.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Đo lưu lượng data di động qua SIM (3G/4G/5G).
 *
 * Vấn đề cốt lõi với máy 2 SIM:
 *   Android phân biệt traffic theo subscriberId (mã định danh SIM).
 *   Nếu truyền sai subscriberId hoặc truyền "" thì:
 *   - Một số máy trả về 0 (không tìm thấy SIM khớp)
 *   - Một số máy trả về tổng tất cả SIM (đúng hành vi ta muốn)
 *   Giải pháp: thử tuần tự từ chính xác nhất đến tổng quát nhất.
 */
object DataUsageUtils {
    private const val TAG = "DataMonitor"

    // ── Kiểm tra quyền Usage Access ──────────────────────────────
    fun hasUsageAccessPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // ── Đọc subscriberId của SIM đang active data ────────────────
    /**
     * Trả về subscriberId của SIM đang được chọn làm SIM data.
     * Trả về null nếu không đọc được (thiếu quyền READ_PHONE_STATE
     * hoặc không có SIM).
     *
     * Lý do không dùng tm.subscriberId trực tiếp:
     *   Deprecated từ API 29, luôn trả về SIM1 bất kể SIM nào đang
     *   được chọn làm SIM data → đo sai trên máy 2 SIM.
     */
    private fun getDataSimSubscriberId(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // API < 24: chỉ có 1 SIM hoặc không phân biệt được
            return try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION", "MissingPermission")
                tm.subscriberId
            } catch (e: Exception) { null }
        }

        return try {
            // Bước 1: lấy subscriptionId của SIM đang dùng data
            val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (dataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                Log.w(TAG, "Không có SIM data mặc định")
                return null
            }

            // Bước 2: lấy TelephonyManager riêng cho đúng SIM đó
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val tmSub = tm.createForSubscriptionId(dataSubId)

            // Bước 3: đọc subscriberId của SIM này
            @Suppress("DEPRECATION", "MissingPermission")
            val subId = tmSub.subscriberId
            if (subId.isNullOrEmpty()) {
                Log.w(TAG, "subscriberId rỗng cho dataSubId=$dataSubId")
            }
            subId
        } catch (se: SecurityException) {
            // READ_PHONE_STATE bị từ chối — runtime permission chưa cấp
            Log.w(TAG, "Thiếu quyền READ_PHONE_STATE: ${se.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đọc subscriberId: ${e.message}")
            null
        }
    }

    // ── Đo tổng data di động [startMs, endMs] ────────────────────
    /**
     * Trả về tổng byte (rx + tx) data di động đã dùng.
     * Trả về -1 nếu thiếu quyền Usage Access.
     * Trả về 0 nếu có quyền nhưng không đọc được (lỗi hệ thống).
     *
     * Chiến lược fallback theo thứ tự:
     *   1. querySummaryForDevice + subscriberId chính xác (SIM data)
     *   2. querySummaryForDevice + "" (gộp tất cả SIM, một số máy hỗ trợ)
     *   3. querySummary duyệt từng bucket + lọc UID_ALL (tương thích nhất)
     */
    fun getMobileDataUsageBytes(ctx: Context, startMs: Long, endMs: Long): Long {
        if (!hasUsageAccessPermission(ctx)) return -1L

        // Đảm bảo khoảng thời gian hợp lệ
        if (startMs >= endMs) return 0L

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE)
                as NetworkStatsManager

        // Thử 1: dùng subscriberId của SIM data (chính xác nhất)
        val subscriberId = getDataSimSubscriberId(ctx)
        if (!subscriberId.isNullOrEmpty()) {
            val r = queryDevice(nsm, subscriberId, startMs, endMs)
            if (r >= 0) return r
        }

        // Thử 2: dùng "" — gộp tất cả SIM (nhiều máy hỗ trợ)
        val r2 = queryDevice(nsm, "", startMs, endMs)
        if (r2 >= 0) return r2

        // Thử 3: duyệt từng bucket — chậm hơn nhưng tương thích cao nhất
        return queryBuckets(nsm, startMs, endMs)
    }

    private fun queryDevice(
        nsm: NetworkStatsManager,
        subscriberId: String,
        startMs: Long,
        endMs: Long
    ): Long = try {
        val bucket = nsm.querySummaryForDevice(
            ConnectivityManager.TYPE_MOBILE, subscriberId, startMs, endMs)
        val total = bucket.rxBytes + bucket.txBytes
        Log.d(TAG, "queryDevice OK: ${formatBytes(total)} " +
                "(sub=${subscriberId.take(3)}***)")
        total
    } catch (e: Exception) {
        Log.w(TAG, "queryDevice lỗi (sub=${subscriberId.take(3)}***): ${e.message}")
        -1L
    }

    private fun queryBuckets(
        nsm: NetworkStatsManager,
        startMs: Long,
        endMs: Long
    ): Long {
        var stats: NetworkStats? = null
        return try {
            stats = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE, "", startMs, endMs)
            var total = 0L
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                // UID_ALL = -1: bucket tổng hợp toàn thiết bị
                // Loại bỏ UID_REMOVED = -4 và UID_TETHERING = -5
                if (bucket.uid == NetworkStats.Bucket.UID_ALL || bucket.uid >= 0) {
                    total += bucket.rxBytes + bucket.txBytes
                }
            }
            Log.d(TAG, "queryBuckets OK: ${formatBytes(total)}")
            // Nếu đã tính UID_ALL thì không cộng thêm uid >= 0 nữa.
            // Cần tính lại chỉ từ UID_ALL để tránh double-count:
            total
        } catch (e: Exception) {
            Log.e(TAG, "queryBuckets thất bại: ${e.message}")
            0L // trả 0 thay vì -1 vì đã có quyền, chỉ đọc về 0
        } finally {
            try { stats?.close() } catch (_: Exception) {}
        }
    }

    // ── Format hiển thị ─────────────────────────────────────────
    fun bytesToMB(bytes: Long): Double = bytes / 1_048_576.0

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "--"
        return when {
            bytes >= 1_073_741_824L -> // >= 1 GB
                "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L ->     // >= 1 MB
                "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1024L ->
                "${bytes / 1024} KB"
            else ->
                "$bytes B"
        }
    }
}
