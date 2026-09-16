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
 * ═══════════════════════════════════════════════════════════════════
 * BUG ĐÃ SỬA TRONG LẦN REVIEW NÀY (quan trọng nhất):
 *
 *   Code cũ dùng điều kiện `if (r > 0) return r` để quyết định 1 chiến
 *   lược có "thành công" hay không. Vấn đề: 0 byte là kết quả HỢP LỆ
 *   (ví dụ vừa sang ngày mới, hoặc ngày lịch sử không dùng data nào).
 *   Khi đó code không phân biệt được "đo được 0 byte" với "query lỗi",
 *   nên LUÔN chạy tiếp cả 6 chiến lược mỗi lần gọi hàm — dù chiến lược
 *   đầu tiên đã trả lời đúng.
 *
 *   Hậu quả thực tế:
 *   - Service nền gọi hàm này mỗi 60 giây → tốn pin không cần thiết
 *   - Màn hình Lịch sử gọi hàm này 30 lần (mỗi ngày) → nếu nhiều ngày
 *     có 0 byte, tổng cộng lên tới 30×6 = 180 lần querySummary, có thể
 *     làm màn hình load chậm/giật trên máy cấu hình thấp
 *
 *   FIX:
 *   1. Phân biệt rõ 3 loại kết quả trả về từ mỗi chiến lược:
 *        -1   = lỗi/exception (thử chiến lược tiếp theo)
 *        >= 0 = kết quả HỢP LỆ kể cả khi = 0 (dừng lại, không thử nữa)
 *   2. Cache lại chiến lược đã thành công lần trước (subscriberId hoặc
 *      phương thức nào) để các lần gọi sau dùng thẳng, bỏ qua dò lại
 *      từ đầu — trừ khi chiến lược cache bị lỗi (SIM rút ra chẳng hạn)
 *      thì mới dò lại toàn bộ.
 * ═══════════════════════════════════════════════════════════════════
 */
object DataUsageUtils {
    private const val TAG = "DataMonitor"

    // ─────────────────────────────────────────────────────────────
    // Cache chiến lược đã thành công — tránh dò lại 6 bước mỗi lần gọi.
    // subscriberIdHint: null = chưa dò, "" = dùng subscriberId rỗng,
    // giá trị khác = dùng đúng subscriberId đó.
    // methodHint: đánh dấu nếu chiến lược thành công là bucket-based.
    // ─────────────────────────────────────────────────────────────
    @Volatile private var cachedSubscriberId: String? = null
    @Volatile private var cachedUseNullSub: Boolean = false
    @Volatile private var cachedUseBucketUidAll: Boolean = false
    @Volatile private var cachedUseBucketPerUid: Boolean = false
    @Volatile private var hasCachedStrategy: Boolean = false

    // ─────────────────────────────────────────────────────────────
    // Quyền Usage Access
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
    // ─────────────────────────────────────────────────────────────
    private fun getAllSubscriberIds(ctx: Context): List<String> {
        val result = mutableListOf<String>()
        try {
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                    as SubscriptionManager
            @Suppress("MissingPermission")
            val subs: List<SubscriptionInfo>? = sm.activeSubscriptionInfoList
            if (subs.isNullOrEmpty()) return result

            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            for (sub in subs) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        @Suppress("DEPRECATION", "MissingPermission")
                        val sid = tm.createForSubscriptionId(sub.subscriptionId).subscriberId
                        if (!sid.isNullOrEmpty()) result.add(sid)
                    } catch (_: Exception) {}
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Thiếu quyền đọc SIM list: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi đọc danh sách SIM: ${e.message}")
        }
        return result
    }

    private fun getDefaultDataSubscriberId(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (dataSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            @Suppress("DEPRECATION", "MissingPermission")
            tm.createForSubscriptionId(dataSubId).subscriberId?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    // ─────────────────────────────────────────────────────────────
    // Đo lưu lượng data di động — entry point chính
    // Trả về: tổng byte (>= 0), -1 nếu thiếu quyền Usage Access
    // ─────────────────────────────────────────────────────────────
    fun getMobileDataUsageBytes(ctx: Context, startMs: Long, endMs: Long): Long {
        if (!hasUsageAccessPermission(ctx)) return -1L
        if (startMs >= endMs) return 0L

        val nsm = ctx.getSystemService(Context.NETWORK_STATS_SERVICE)
                as NetworkStatsManager

        // ── Bước nhanh: dùng lại chiến lược đã biết thành công lần trước ──
        if (hasCachedStrategy) {
            val fast = tryFromCache(nsm, startMs, endMs)
            if (fast != null) return fast
            // Cache không còn hợp lệ (VD: SIM bị rút) → dò lại từ đầu bên dưới
            hasCachedStrategy = false
        }

        // ── Dò tuần tự từng chiến lược, dừng ngay khi có kết quả HỢP LỆ ──
        // (>= 0 nghĩa là hợp lệ, kể cả 0 byte — không coi 0 là "thất bại")

        // 1. SIM data mặc định
        getDefaultDataSubscriberId(ctx)?.let { sub ->
            val r = queryDevice(nsm, sub, startMs, endMs)
            if (r >= 0) return cacheAndReturn(sub, false, false, false, r)
        }

        // 2. Từng subscriberId của tất cả SIM
        for (sub in getAllSubscriberIds(ctx)) {
            val r = queryDevice(nsm, sub, startMs, endMs)
            if (r >= 0) return cacheAndReturn(sub, false, false, false, r)
        }

        // 3. subscriberId = null (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val r = queryDevice(nsm, null, startMs, endMs)
            if (r >= 0) return cacheAndReturn(null, true, false, false, r)
        }

        // 4. subscriberId = ""
        val r4 = queryDevice(nsm, "", startMs, endMs)
        if (r4 >= 0) return cacheAndReturn("", false, false, false, r4)

        // 5. querySummary → chỉ lấy UID_ALL
        val r5 = queryBucketsUidAll(nsm, startMs, endMs)
        if (r5 >= 0) return cacheAndReturn(null, false, true, false, r5)

        // 6. Cuối cùng: cộng từng UID riêng lẻ
        val r6 = queryBucketsPerUid(nsm, startMs, endMs)
        return cacheAndReturn(null, false, false, true, r6.coerceAtLeast(0L))
    }

    /** Thử lại đúng chiến lược đã cache — trả null nếu chiến lược đó lỗi (-1) */
    private fun tryFromCache(nsm: NetworkStatsManager, startMs: Long, endMs: Long): Long? {
        val r = when {
            cachedUseBucketPerUid -> queryBucketsPerUid(nsm, startMs, endMs).let {
                if (it >= 0) it else -1L
            }
            cachedUseBucketUidAll -> queryBucketsUidAll(nsm, startMs, endMs)
            cachedUseNullSub      -> queryDevice(nsm, null, startMs, endMs)
            else                  -> queryDevice(nsm, cachedSubscriberId, startMs, endMs)
        }
        return if (r >= 0) r else null
    }

    private fun cacheAndReturn(
        subscriberId: String?, useNull: Boolean,
        useBucketUidAll: Boolean, useBucketPerUid: Boolean,
        result: Long
    ): Long {
        cachedSubscriberId    = subscriberId
        cachedUseNullSub      = useNull
        cachedUseBucketUidAll = useBucketUidAll
        cachedUseBucketPerUid = useBucketPerUid
        hasCachedStrategy     = true
        return result
    }

    // ─────────────────────────────────────────────────────────────
    // querySummaryForDevice: nhanh, 1 lần gọi, trả về bucket tổng
    // Trả về -1 nếu lỗi, >= 0 nếu hợp lệ (kể cả 0 byte)
    // ─────────────────────────────────────────────────────────────
    private fun queryDevice(
        nsm: NetworkStatsManager,
        subscriberId: String?,
        startMs: Long,
        endMs: Long
    ): Long = try {
        val bucket = nsm.querySummaryForDevice(
            ConnectivityManager.TYPE_MOBILE, subscriberId, startMs, endMs)
        bucket.rxBytes + bucket.txBytes
    } catch (e: Exception) {
        Log.w(TAG, "queryDevice(sub=${subscriberId?.take(4)}***) lỗi: ${e.message}")
        -1L
    }

    // ─────────────────────────────────────────────────────────────
    // querySummary → chỉ lấy bucket UID_ALL (tổng hợp toàn thiết bị)
    // Trả về -1 nếu lỗi, >= 0 nếu hợp lệ (kể cả không có bucket UID_ALL)
    // ─────────────────────────────────────────────────────────────
    private fun queryBucketsUidAll(
        nsm: NetworkStatsManager, startMs: Long, endMs: Long
    ): Long {
        var stats: NetworkStats? = null
        return try {
            stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, "", startMs, endMs)
            val bucket = NetworkStats.Bucket()
            var total = 0L
            var found = false
            while (stats.hasNextBucket()) {
                stats.getNextBucket(bucket)
                if (bucket.uid == NetworkStats.Bucket.UID_ALL) {
                    total += bucket.rxBytes + bucket.txBytes
                    found = true
                }
            }
            if (found) total else -1L  // không có bucket UID_ALL → coi là lỗi, thử tiếp
        } catch (e: Exception) {
            Log.w(TAG, "queryBucketsUidAll lỗi: ${e.message}")
            -1L
        } finally {
            try { stats?.close() } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Cộng từng UID riêng lẻ — chiến lược cuối cùng, chậm nhất
    // ─────────────────────────────────────────────────────────────
    private fun queryBucketsPerUid(
        nsm: NetworkStatsManager, startMs: Long, endMs: Long
    ): Long {
        for (sub in listOf<String?>("", null)) {
            var stats: NetworkStats? = null
            try {
                stats = nsm.querySummary(ConnectivityManager.TYPE_MOBILE, sub, startMs, endMs)
                val bucket = NetworkStats.Bucket()
                var total = 0L
                var hasData = false
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    if (bucket.uid >= 0) {
                        total += bucket.rxBytes + bucket.txBytes
                        hasData = true
                    }
                }
                if (hasData) return total
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
