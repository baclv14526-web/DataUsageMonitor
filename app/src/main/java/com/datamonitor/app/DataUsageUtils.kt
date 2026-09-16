package com.datamonitor.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Đọc lưu lượng data di động (3G/4G/5G) đã dùng bằng NetworkStatsManager.
 * Gộp chung mọi loại mạng di động (RAT) vì Android không tách rời 3G/4G/5G
 * ở tầng thống kê lưu lượng - tất cả đều thuộc TRANSPORT_MOBILE / TYPE_MOBILE.
 */
object DataUsageUtils {
    private const val TAG = "DataUsageUtils"

    /** Kiểm tra ứng dụng đã được cấp quyền "Truy cập sử dụng" (Usage Access) chưa */
    fun hasUsageAccessPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Trả về tổng số byte data di động (rx+tx) đã dùng từ [startMillis] đến [endMillis].
     * Trả về -1 nếu không thể đọc được (thiếu quyền hoặc lỗi hệ thống).
     */
    fun getMobileDataUsageBytes(context: Context, startMillis: Long, endMillis: Long): Long {
        if (!hasUsageAccessPermission(context)) return -1L

        return try {
            val networkStatsManager =
                context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

            val telephonyManager =
                context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val subscriberId = try {
                @Suppress("DEPRECATION")
                telephonyManager.subscriberId ?: ""
            } catch (e: SecurityException) {
                ""
            }

            val bucket = networkStatsManager.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE,
                subscriberId,
                startMillis,
                endMillis
            )
            (bucket.rxBytes + bucket.txBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể đọc lưu lượng data: ${e.message}")
            // Phương án dự phòng: cộng dồn từng bucket qua NetworkStats
            try {
                fallbackQuery(context, startMillis, endMillis)
            } catch (ex: Exception) {
                Log.e(TAG, "Fallback cũng lỗi: ${ex.message}")
                -1L
            }
        }
    }

    private fun fallbackQuery(context: Context, startMillis: Long, endMillis: Long): Long {
        val networkStatsManager =
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val stats: NetworkStats = networkStatsManager.querySummary(
            ConnectivityManager.TYPE_MOBILE, "", startMillis, endMillis
        )
        var total = 0L
        val bucket = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            total += bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        return total
    }

    fun bytesToMB(bytes: Long): Double = bytes / 1024.0 / 1024.0

    fun formatMB(bytes: Long): String {
        val mb = bytesToMB(bytes)
        return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0)
        else String.format("%.1f MB", mb)
    }
}
