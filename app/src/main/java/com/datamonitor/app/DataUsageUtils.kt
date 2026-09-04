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

object DataUsageUtils {
    private const val TAG = "DataUsageUtils"

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

    /**
     * Trả về tổng byte data di động (3G/4G/5G) đã dùng trong khoảng [startMs, endMs].
     * Trả về -1 nếu thiếu quyền hoặc lỗi.
     */
    fun getMobileDataUsageBytes(ctx: Context, startMs: Long, endMs: Long): Long {
        if (!hasUsageAccessPermission(ctx)) return -1L

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        // Lấy subscriberId — cần READ_PHONE_STATE, có thể null trên một số máy
        val subId = try {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION")
            tm.subscriberId ?: ""
        } catch (e: SecurityException) {
            Log.w(TAG, "Không đọc được subscriberId: ${e.message}")
            ""
        }

        return try {
            // querySummaryForDevice: nhanh, trả về 1 Bucket tổng hợp
            val bucket = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, subId, startMs, endMs
            )
            bucket.rxBytes + bucket.txBytes
        } catch (e: Exception) {
            Log.w(TAG, "querySummaryForDevice lỗi, thử fallback: ${e.message}")
            // Fallback: duyệt từng Bucket (chậm hơn nhưng tương thích rộng hơn)
            fallbackQuery(nsm, startMs, endMs)
        }
    }

    private fun fallbackQuery(nsm: NetworkStatsManager, startMs: Long, endMs: Long): Long {
        return try {
            val stats: NetworkStats = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE, "", startMs, endMs
            )
            var total = 0L
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                total += bucket.rxBytes + bucket.txBytes
            }
            stats.close()
            total
        } catch (e: Exception) {
            Log.e(TAG, "Fallback cũng thất bại: ${e.message}")
            -1L
        }
    }

    fun bytesToMB(bytes: Long): Double = bytes / 1_048_576.0

    fun formatBytes(bytes: Long): String {
        val mb = bytesToMB(bytes)
        return when {
            mb >= 1024 -> "%.2f GB".format(mb / 1024.0)
            mb >= 1    -> "%.1f MB".format(mb)
            else       -> "${bytes / 1024} KB"
        }
    }
}
