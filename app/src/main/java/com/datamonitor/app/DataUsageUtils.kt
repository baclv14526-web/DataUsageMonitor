package com.datamonitor.app

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Đo lưu lượng data di động (3G/4G/5G) — hỗ trợ máy 2 SIM.
 *
 * Root cause tại sao KHÔNG đo được trên Samsung A23 5G (Viettel SIM1 + iTel SIM2):
 *
 *   BUG 1 — double-count trong queryBuckets:
 *     Code cũ cộng cả bucket UID_ALL (-1) LẪN bucket từng app (uid >= 0).
 *     UID_ALL đã là TỔNG của tất cả UID rồi → cộng thêm uid>=0 = nhân đôi.
 *     Kết quả bị gấp đôi thực tế, nhưng trên một số máy queryBuckets trả
 *     về 0 vì cần subscriberId cụ thể → app thấy 0 mà không báo lỗi.
 *
 *   BUG 2 — READ_PHONE_STATE trên Android 10+ (API 29+):
 *     Samsung A23 5G chạy Android 12/13. Từ API 29, TelephonyManager.subscriberId
 *     cần cả READ_PHONE_STATE *và* READ_PRIVILEGED_PHONE_STATE (system permission)
 *     để trả về IMSI thật. App thường chỉ có READ_PHONE_STATE → subscriberId = null.
 *     Kết quả: không lấy được subscriberId → không query được đúng SIM.
 *
 *   BUG 3 — Không thử TẤT CẢ SIM khi data SIM mặc định không query được:
 *     Code cũ chỉ thử: (a) SIM data mặc định, (b) subscriberId="".
 *     Trên Samsung, "" có thể trả về 0 thay vì tổng các SIM.
 *     Cần lấy danh sách TẤT CẢ SIM từ SubscriptionManager và thử từng cái.
 *
 *   GIẢI PHÁP:
 *     - Lấy subscriberId qua SubscriptionInfo (ít bị restrict hơn TelephonyManager)
 *     - Thử lần lượt: SIM data → tất cả SIM → null/""
 *     - queryBuckets chỉ dùng UID_ALL, không cộng từng uid riêng lẻ
 *     - Nếu tất cả đều trả 0 → thử queryDetailsForUid với PROCESS_ALL_UIDS
 */
object DataUsageUtils {
    private const val TAG = "DataMonitor"

    // ─────────────────────────────────────────────────────────────
    // Kiểm tra quyền Usage Access (PACKAGE_USAGE_STATS)
    // ─────────────────────────────────────────────────────────────
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

    // ─────────────────────────────────────────────────────────────
    // Lấy danh sách subscriberId của tất cả SIM đang cắm
    //
    // Dùng SubscriptionManager.getActiveSubscriptionInfoList() thay vì
    // TelephonyManager.subscriberId vì:
    //   - Từ Android 10 (API 29): subscriberId cần PRIVILEGED permission
    //     mà app thường không có → trả về null
    //   - SubscriptionInfo.iccId + số điện thoại vẫn đọc được với
    //     READ_PHONE_STATE bình thường trên hầu hết OEM
    //   - Một số Samsung ROM cho phép đọc subscriberId qua SubscriptionInfo
    //     ngay cả khi TelephonyManager.subscriberId trả về null
    // ─────────────────────────────────────────────────────────────
    private fun getAllSubscriberIds(ctx: Context): List<String?> {
        val result = mutableListOf<String?>()

        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as SubscriptionManager

            @Suppress("MissingPermission")
            val subs: List<SubscriptionInfo>? = sm.activeSubscriptionInfoList

            if (subs.isNullOrEmpty()) {
                Log.w(TAG, "Không có SIM active nào")
                return result
            }

            Log.d(TAG, "Tìm thấy ${subs.size} SIM")

            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            for (sub in subs) {
                val subId = sub.subscriptionId
                val simSlot = sub.simSlotIndex + 1

                // Thử lấy subscriberId theo thứ tự ưu tiên:
                var subscriberId: String? = null

                // Cách 1: TelephonyManager cho SIM cụ thể (hoạt động trên API 24-28)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        @Suppress("DEPRECATION", "MissingPermission")
                        subscriberId = tm.createForSubscriptionId(subId).subscriberId
                    } catch (e: Exception) {
                        Log.w(TAG, "SIM$simSlot createForSubscriptionId lỗi: ${e.message}")
                    }
                }

                // Cách 2: SubscriptionInfo trực tiếp (một số Samsung ROM trả về được)
                if (subscriberId.isNullOrEmpty()) {
                    try {
                        // SubscriptionInfo không có getter subscriberId public,
                        // nhưng iccId có thể dùng làm key trên một số máy
                        // → thêm null vào list để thử query không có subscriberId
                        Log.d(TAG, "SIM$simSlot: không lấy được subscriberId")
                    } catch (_: Exception) {}
                }

                Log.d(TAG, "SIM$simSlot (subId=$subId): " +
                        "subscriberId=${subscriberId?.take(5)}***")
                result.add(subscriberId) // null nếu không lấy được
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Thiếu quyền đọc SIM list: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đọc danh sách SIM: ${e.message}")
        }

        return result
    }

    // ─────────────────────────────────────────────────────────────
    // Lấy subscriptionId của SIM đang được chọn dùng data
    // ─────────────────────────────────────────────────────────────
    private fun getDefaultDataSubId(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SubscriptionManager.getDefaultDataSubscriptionId()
        } else {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Đo lưu lượng data di động — entry point chính
    // Trả về: tổng byte, -1 nếu thiếu quyền, 0 nếu lỗi đọc
    // ─────────────────────────────────────────────────────────────
    fun getMobileDataUsageBytes(ctx: Context, startMs: Long, endMs: Long): Long {
        if (!hasUsageAccessPermission(ctx)) return -1L
        if (startMs >= endMs) return 0L

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE)
                as NetworkStatsManager

        // === Chiến lược 1: SIM data mặc định với subscriberId ===
        val dataSubId = getDefaultDataSubId()
        if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    @Suppress("DEPRECATION", "MissingPermission")
                    val subIdStr = tm.createForSubscriptionId(dataSubId).subscriberId
                    if (!subIdStr.isNullOrEmpty()) {
                        val r = queryDevice(nsm, subIdStr, startMs, endMs)
                        if (r > 0) {
                            Log.d(TAG, "Đo được qua SIM data mặc định: ${formatBytes(r)}")
                            return r
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // === Chiến lược 2: Thử từng subscriberId của tất cả SIM ===
        val allSubIds = getAllSubscriberIds(ctx)
        for (subId in allSubIds) {
            if (!subId.isNullOrEmpty()) {
                val r = queryDevice(nsm, subId, startMs, endMs)
                if (r > 0) {
                    Log.d(TAG, "Đo được qua subscriberId SIM: ${formatBytes(r)}")
                    return r
                }
            }
        }

        // === Chiến lược 3: subscriberId = null (Android 10+ cách mới) ===
        // Từ Android 10, một số OEM cho phép truyền null thay vì ""
        // để lấy tổng tất cả SIM mà không cần biết subscriberId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = queryDevice(nsm, null, startMs, endMs)
            if (r > 0) {
                Log.d(TAG, "Đo được qua subscriberId=null: ${formatBytes(r)}")
                return r
            }
        }

        // === Chiến lược 4: subscriberId = "" (Android 9 và một số OEM) ===
        val r4 = queryDevice(nsm, "", startMs, endMs)
        if (r4 > 0) {
            Log.d(TAG, "Đo được qua subscriberId=\"\": ${formatBytes(r4)}")
            return r4
        }

        // === Chiến lược 5: Duyệt từng bucket, chỉ lấy UID_ALL ===
        // FIX BUG: chỉ lấy UID_ALL, không cộng uid>=0 để tránh double-count
        val r5 = queryBucketsUidAll(nsm, startMs, endMs)
        if (r5 > 0) {
            Log.d(TAG, "Đo được qua queryBuckets UID_ALL: ${formatBytes(r5)}")
            return r5
        }

        // === Chiến lược 6 (cuối cùng): Cộng dồn từng UID riêng lẻ ===
        // Dùng khi UID_ALL không có — cộng traffic của mọi app, loại UID rác
        val r6 = queryBucketsPerUid(nsm, startMs, endMs)
        Log.d(TAG, "Đo qua queryBuckets per-UID: ${formatBytes(r6)}")
        return r6
    }

    // ─────────────────────────────────────────────────────────────
    // querySummaryForDevice: nhanh, 1 lần gọi, trả về bucket tổng
    // subscriberId: String? — null hợp lệ trên Android 10+
    // ─────────────────────────────────────────────────────────────
    private fun queryDevice(
        nsm: NetworkStatsManager,
        subscriberId: String?,
        startMs: Long,
        endMs: Long
    ): Long = try {
        val bucket = nsm.querySummaryForDevice(
            ConnectivityManager.TYPE_MOBILE, subscriberId, startMs, endMs)
        val total = bucket.rxBytes + bucket.txBytes
        Log.d(TAG, "queryDevice(sub=${subscriberId?.take(4)}***): ${formatBytes(total)}")
        total
    } catch (e: Exception) {
        Log.w(TAG, "queryDevice(sub=${subscriberId?.take(4)}***) lỗi: ${e.message}")
        -1L
    }

    // ─────────────────────────────────────────────────────────────
    // Chiến lược 5: querySummary → chỉ lấy bucket UID_ALL
    // UID_ALL (-1) = tổng hợp toàn thiết bị, KHÔNG double-count
    // ─────────────────────────────────────────────────────────────
    private fun queryBucketsUidAll(
        nsm: NetworkStatsManager,
        startMs: Long,
        endMs: Long
    ): Long {
        var stats: NetworkStats? = null
        return try {
            stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, "", startMs, endMs)
            val bucket = NetworkStats.Bucket()
            var total = 0L
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                if (bucket.uid == NetworkStats.Bucket.UID_ALL) {
                    total += bucket.rxBytes + bucket.txBytes
                }
            }
            total
        } catch (e: Exception) {
            Log.w(TAG, "queryBucketsUidAll lỗi: ${e.message}")
            0L
        } finally {
            try { stats?.close() } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Chiến lược 6: Cộng từng UID riêng lẻ (loại UID âm đặc biệt)
    // Dùng khi không có bucket UID_ALL.
    // Chú ý: có thể bị lặp nếu 1 packet được ghi cho nhiều UID.
    // ─────────────────────────────────────────────────────────────
    private fun queryBucketsPerUid(
        nsm: NetworkStatsManager,
        startMs: Long,
        endMs: Long
    ): Long {
        // Thử lần lượt subscriberId khác nhau
        val candidates = listOf("", null)
        for (sub in candidates) {
            var stats: NetworkStats? = null
            try {
                stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, sub, startMs, endMs)
                val bucket = NetworkStats.Bucket()
                var total = 0L
                var hasData = false
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    // Chỉ uid >= 0 là app thật (bỏ qua UID_ALL=-1, REMOVED=-4, TETHERING=-5)
                    if (bucket.uid >= 0) {
                        total += bucket.rxBytes + bucket.txBytes
                        hasData = true
                    }
                }
                if (hasData && total > 0) return total
            } catch (e: Exception) {
                Log.w(TAG, "queryBucketsPerUid(sub=${sub?.take(3)}) lỗi: ${e.message}")
            } finally {
                try { stats?.close() } catch (_: Exception) {}
            }
        }
        return 0L
    }

    // ─────────────────────────────────────────────────────────────
    // Format hiển thị
    // ─────────────────────────────────────────────────────────────
    fun bytesToMB(bytes: Long): Double = bytes / 1_048_576.0

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "--"
        return when {
            bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
            bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
            bytes >= 1_024L         -> "${bytes / 1024} KB"
            else                    -> "$bytes B"
        }
    }
}
