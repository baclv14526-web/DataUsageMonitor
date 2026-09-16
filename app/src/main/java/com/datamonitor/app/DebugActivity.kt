package com.datamonitor.app

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Màn hình debug — hiển thị chi tiết từng bước đo data.
 * Mục đích: xác định chính xác chiến lược nào hoạt động
 * trên từng máy/ROM cụ thể (Samsung, Oppo, Realme...).
 *
 * Truy cập: bấm nút "Chạy Debug" trong MainActivity.
 */
class DebugActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private val log = StringBuilder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        val btnRun = Button(this).apply {
            text = "▶ Chạy kiểm tra đo data"
        }

        tvLog = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)  // cho phép copy log
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 16, 0, 0)
        }

        val scroll = ScrollView(this).apply {
            addView(tvLog)
        }

        root.addView(btnRun)
        root.addView(scroll)
        setContentView(root)
        title = "Debug Đo Data"

        btnRun.setOnClickListener {
            btnRun.isEnabled = false
            btnRun.text = "Đang chạy..."
            log.clear()
            runDiagnostic {
                btnRun.isEnabled = true
                btnRun.text = "▶ Chạy lại"
            }
        }
    }

    private fun runDiagnostic(onDone: () -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { collectDiagnostic() }
            tvLog.text = result
            onDone()
        }
    }

    private fun collectDiagnostic(): String {
        val sb = StringBuilder()
        fun line(s: String = "") = sb.appendLine(s)
        fun section(title: String) {
            line()
            line("═══ $title ═══")
        }
        fun ok(s: String)   = line("✅ $s")
        fun warn(s: String) = line("⚠️  $s")
        fun err(s: String)  = line("❌ $s")
        fun info(s: String) = line("   $s")

        val startMs = Prefs.startOfTodayMillis()
        val endMs   = System.currentTimeMillis()

        // ── Thông tin thiết bị ───────────────────────────────────
        section("THIẾT BỊ")
        info("Hãng  : ${Build.MANUFACTURER} ${Build.MODEL}")
        info("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        info("ROM   : ${Build.DISPLAY}")

        // ── Quyền ───────────────────────────────────────────────
        section("QUYỀN")
        if (DataUsageUtils.hasUsageAccessPermission(this)) {
            ok("Usage Access: CÓ")
        } else {
            err("Usage Access: KHÔNG → app không thể đo data")
            return sb.toString()
        }

        // ── Thông tin SIM ────────────────────────────────────────
        section("THÔNG TIN SIM")
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        info("Số SIM: ${tm.phoneCount}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            info("SIM data mặc định subId: $dataSubId")

            try {
                @Suppress("MissingPermission")
                val subs = sm.activeSubscriptionInfoList
                if (subs.isNullOrEmpty()) {
                    warn("Không đọc được danh sách SIM active")
                } else {
                    for (sub in subs) {
                        val slot = sub.simSlotIndex + 1
                        val subId = sub.subscriptionId
                        val carrier = sub.carrierName ?: "?"
                        val isData = if (subId == dataSubId) " ← SIM DATA" else ""

                        info("SIM$slot: subId=$subId carrier=$carrier$isData")

                        // Thử lấy subscriberId cho SIM này
                        try {
                            @Suppress("DEPRECATION", "MissingPermission")
                            val sid = tm.createForSubscriptionId(subId).subscriberId
                            if (sid.isNullOrEmpty()) {
                                warn("  subscriberId: null/rỗng (Android 10+ hạn chế)")
                            } else {
                                ok("  subscriberId: ${sid.take(5)}***${sid.takeLast(3)} (độ dài ${sid.length})")
                            }
                        } catch (e: Exception) {
                            err("  subscriberId: lỗi — ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                err("Lỗi đọc danh sách SIM: ${e.message}")
            }
        } else {
            info("API < 24 — không hỗ trợ đa SIM API")
        }

        // ── Thử từng chiến lược đo ───────────────────────────────
        section("KẾT QUẢ ĐO DATA (hôm nay)")
        val nsm = getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager

        // Chiến lược 1: SIM data mặc định
        line()
        line("── Chiến lược 1: SIM data mặc định ──")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val dataSubId = SubscriptionManager.getDefaultDataSubscriptionId()
            if (dataSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                try {
                    @Suppress("DEPRECATION", "MissingPermission")
                    val subIdStr = tm.createForSubscriptionId(dataSubId).subscriberId
                    if (!subIdStr.isNullOrEmpty()) {
                        tryQuery(sb, nsm, subIdStr, startMs, endMs,
                            "querySummaryForDevice(subId SIM data)")
                    } else {
                        warn("subscriberId null — bỏ qua chiến lược 1")
                    }
                } catch (e: Exception) {
                    err("Lỗi lấy subscriberId SIM data: ${e.message}")
                }
            } else {
                warn("Không có SIM data mặc định")
            }
        }

        // Chiến lược 2: subscriberId = null (Android 10+)
        line()
        line("── Chiến lược 2: subscriberId = null (API 29+) ──")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tryQuery(sb, nsm, null, startMs, endMs,
                "querySummaryForDevice(null)")
        } else {
            info("Bỏ qua — API < 29")
        }

        // Chiến lược 3: subscriberId = ""
        line()
        line("── Chiến lược 3: subscriberId = \"\" ──")
        tryQuery(sb, nsm, "", startMs, endMs,
            "querySummaryForDevice(\"\")")

        // Chiến lược 4: querySummary lấy UID_ALL
        line()
        line("── Chiến lược 4: querySummary → UID_ALL ──")
        var statsUidAll: android.app.usage.NetworkStats? = null
        try {
            statsUidAll = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE, "", startMs, endMs)
            val bucket = android.app.usage.NetworkStats.Bucket()
            var total = 0L
            var found = false
            while (statsUidAll.hasNextBucket()) {
                statsUidAll.getNextBucket(bucket)
                if (bucket.uid == android.app.usage.NetworkStats.Bucket.UID_ALL) {
                    total += bucket.rxBytes + bucket.txBytes
                    found = true
                }
            }
            if (found && total > 0) ok("UID_ALL: ${DataUsageUtils.formatBytes(total)}")
            else warn("UID_ALL bucket không có hoặc = 0")
        } catch (e: Exception) {
            err("querySummary UID_ALL lỗi: ${e.message}")
        } finally {
            try { statsUidAll?.close() } catch (_: Exception) {} }

        // Chiến lược 5: querySummary cộng từng uid >= 0
        line()
        line("── Chiến lược 5: querySummary → per-UID ──")
        var statsPerUid: android.app.usage.NetworkStats? = null
        try {
            statsPerUid = nsm.querySummary(
                ConnectivityManager.TYPE_MOBILE, "", startMs, endMs)
            val bucket = android.app.usage.NetworkStats.Bucket()
            var total = 0L
            val uidMap = mutableMapOf<Int, Long>()
            while (statsPerUid.hasNextBucket()) {
                statsPerUid.getNextBucket(bucket)
                if (bucket.uid >= 0) {
                    val bytes = bucket.rxBytes + bucket.txBytes
                    uidMap[bucket.uid] = (uidMap[bucket.uid] ?: 0L) + bytes
                    total += bytes
                }
            }
            if (total > 0) {
                ok("Tổng per-UID: ${DataUsageUtils.formatBytes(total)}")
                info("Top 5 UID dùng nhiều nhất:")
                uidMap.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .forEach { (uid, bytes) ->
                        val pkgName = try {
                            packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid=$uid"
                        } catch (_: Exception) { "uid=$uid" }
                        info("  $pkgName: ${DataUsageUtils.formatBytes(bytes)}")
                    }
            } else {
                warn("per-UID tổng = 0")
            }
        } catch (e: Exception) {
            err("querySummary per-UID lỗi: ${e.message}")
        } finally {
            try { statsPerUid?.close() } catch (_: Exception) {} }

        // ── Kết quả cuối từ getMobileDataUsageBytes ──────────────
        section("KẾT QUẢ HÀM CHÍNH")
        val final = DataUsageUtils.getMobileDataUsageBytes(this, startMs, endMs)
        when {
            final < 0  -> err("Thiếu quyền Usage Access")
            final == 0L -> warn("Trả về 0 — chưa đo được (xem log các chiến lược trên)")
            else       -> ok("Data hôm nay: ${DataUsageUtils.formatBytes(final)}")
        }

        line()
        line("Thời gian: ${java.text.SimpleDateFormat(
            "HH:mm:ss dd/MM/yyyy", java.util.Locale.getDefault()
        ).format(java.util.Date())}")

        return sb.toString()
    }

    private fun tryQuery(
        sb: StringBuilder,
        nsm: NetworkStatsManager,
        subscriberId: String?,
        startMs: Long,
        endMs: Long,
        label: String
    ) {
        fun ok(s: String)   = sb.appendLine("✅ $s")
        fun warn(s: String) = sb.appendLine("⚠️  $s")
        fun err(s: String)  = sb.appendLine("❌ $s")

        try {
            val bucket = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, subscriberId, startMs, endMs)
            val total = bucket.rxBytes + bucket.txBytes
            if (total > 0)
                ok("$label → ${DataUsageUtils.formatBytes(total)}")
            else
                warn("$label → 0 bytes (query OK nhưng không có data)")
        } catch (e: Exception) {
            err("$label → EXCEPTION: ${e.message}")
        }
    }

    override fun onDestroy() {
        // Hủy coroutine đang chạy nếu người dùng đóng màn hình giữa chừng
        // (tránh leak khi activity bị destroy trong lúc query I/O đang chạy)
        scope.cancel()
        super.onDestroy()
    }
}
