package com.datamonitor.app

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Màn hình lịch sử lưu lượng data theo ngày.
 * Hiển thị 30 ngày gần nhất, mỗi ngày có:
 *  - Ngày tháng
 *  - Số MB/GB đã dùng
 *  - Thanh progress so với hạn mức
 *  - Nhãn trạng thái (Bình thường / Cảnh báo / Vượt hạn)
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var tvTotal: TextView
    private lateinit var progressBar: ProgressBar
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build layout bằng code — không cần thêm file XML mới
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF0F4F8.toInt())
        }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1565C0.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        tvTotal = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            text = "Đang tính..."
        }
        val tvSub = TextView(this).apply {
            textSize = 12f
            setTextColor(0xCCFFFFFF.toInt())
            text = "Lịch sử 30 ngày gần nhất"
        }
        progressBar = ProgressBar(this, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).also {
                it.topMargin = dp(8)
            }
            visibility = View.VISIBLE
        }
        header.addView(tvTotal)
        header.addView(tvSub)
        header.addView(progressBar)
        root.addView(header)

        // Danh sách ngày
        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(4)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        title = "Lịch sử sử dụng data"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadHistory()
    }

    private fun loadHistory() {
        scope.launch {
            val items = withContext(Dispatchers.IO) { buildHistoryItems() }
            progressBar.visibility = View.GONE

            if (items.isEmpty()) {
                tvTotal.text = "Không có dữ liệu (cần cấp quyền Truy cập sử dụng)"
                return@launch
            }

            // Tính tổng 7 ngày và 30 ngày
            val total30 = items.sumOf { it.bytes }.coerceAtLeast(0L)
            val total7  = items.take(7).sumOf { it.bytes }.coerceAtLeast(0L)
            tvTotal.text = "7 ngày: ${DataUsageUtils.formatBytes(total7)}" +
                    "   •   30 ngày: ${DataUsageUtils.formatBytes(total30)}"

            listView.adapter = HistoryAdapter(this@HistoryActivity, items)
        }
    }

    private fun buildHistoryItems(): List<DayUsage> {
        // BUG ĐÃ SỬA: code cũ gọi .coerceAtLeast(0L) lên kết quả của
        // getMobileDataUsageBytes() — hàm này trả về -1L khi THIẾU
        // QUYỀN Usage Access. coerceAtLeast(0L) biến -1 thành 0, khiến
        // app hiểu nhầm "thiếu quyền" thành "dùng 0 byte hợp lệ".
        //
        // Hậu quả: vòng lặp 30 ngày luôn trả về đủ 30 item (dù thiếu
        // quyền hay không), nên điều kiện `if (items.isEmpty())` ở
        // loadHistory() — vốn được viết để hiện thông báo "cần cấp
        // quyền" — KHÔNG BAO GIỜ đúng. Người dùng thiếu quyền sẽ thấy
        // toàn bộ 30 ngày là "0 B / Bình thường" thay vì thông báo lỗi
        // rõ ràng, dễ hiểu nhầm là app đang hoạt động tốt.
        //
        // FIX: kiểm tra quyền MỘT LẦN trước vòng lặp, trả về danh sách
        // rỗng ngay nếu thiếu quyền — đúng như logic loadHistory() đã
        // được thiết kế để xử lý.
        if (!DataUsageUtils.hasUsageAccessPermission(this)) {
            return emptyList()
        }

        val limitMB = Prefs.getDailyLimitMB(this)
        val result  = mutableListOf<DayUsage>()
        val cal     = Calendar.getInstance()
        // Tạo 1 lần, tái sử dụng cho cả 30 ngày thay vì tạo mới mỗi vòng lặp
        val dateFormat = SimpleDateFormat("dd/MM/yyyy (EEE)", Locale("vi"))

        // Ngày hôm nay đứng đầu (index 0), đi ngược về 29 ngày trước
        for (i in 0 until 30) {
            val dayLabel = when (i) {
                0    -> "Hôm nay"
                1    -> "Hôm qua"
                else -> dateFormat.format(cal.time)
            }

            // Tính startMs = 00:00:00 của ngày cal
            val startMs = Calendar.getInstance().apply {
                timeInMillis = cal.timeInMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            // endMs = 23:59:59.999 của ngày đó (hoặc "now" nếu là hôm nay)
            val endMs = if (i == 0) System.currentTimeMillis()
                        else startMs + 86_399_999L

            // Đã kiểm tra quyền ở trên nên không cần coerceAtLeast(0L)
            // che lỗi nữa — nếu có lỗi bất thường giữa chừng (hiếm gặp,
            // ví dụ quyền bị thu hồi ngay lúc đang load), vẫn hiển thị
            // 0 cho riêng ngày đó thay vì làm hỏng cả danh sách.
            val bytes = DataUsageUtils.getMobileDataUsageBytes(this, startMs, endMs)
                .let { if (it < 0) 0L else it }

            result.add(DayUsage(
                label   = dayLabel,
                bytes   = bytes,
                limitMB = limitMB,
                isToday = (i == 0)
            ))

            // Lùi lại 1 ngày
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return result
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()
}

// ── Data class một ngày ──────────────────────────────────────────────
data class DayUsage(
    val label:   String,
    val bytes:   Long,
    val limitMB: Long,
    val isToday: Boolean
) {
    val usedMB  get() = DataUsageUtils.bytesToMB(bytes)
    val percent get() = if (limitMB > 0) ((usedMB / limitMB) * 100).toInt().coerceIn(0, 100)
                        else 0
    val status  get() = when {
        percent >= 100 -> "Vượt hạn" to 0xFFD32F2F.toInt()
        percent >= 80  -> "Cảnh báo" to 0xFFF57C00.toInt()
        else           -> "Bình thường" to 0xFF2E7D32.toInt()
    }
}

// ── Adapter danh sách ngày ───────────────────────────────────────────
class HistoryAdapter(
    private val ctx: android.content.Context,
    private val items: List<DayUsage>
) : BaseAdapter() {

    override fun getCount() = items.size
    override fun getItem(pos: Int) = items[pos]
    override fun getItemId(pos: Int) = pos.toLong()

    override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup): View {
        val item = items[pos]
        val dm   = ctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        // Card container
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (item.isToday) 0xFFE3F2FD.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // Hàng 1: nhãn ngày + số liệu + trạng thái
        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvLabel = TextView(ctx).apply {
            text = item.label
            textSize = 14f
            setTextColor(0xFF212121.toInt())
            if (item.isToday) setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvBytes = TextView(ctx).apply {
            text = DataUsageUtils.formatBytes(item.bytes)
            textSize = 15f
            setTextColor(0xFF1565C0.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }

        val (statusText, statusColor) = item.status
        val tvStatus = TextView(ctx).apply {
            text = statusText
            textSize = 11f
            setTextColor(statusColor)
        }

        row1.addView(tvLabel)
        row1.addView(tvBytes)
        row1.addView(tvStatus)
        card.addView(row1)

        // Thanh progress
        val pb = ProgressBar(ctx, null,
            android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(6)).also {
                it.topMargin = dp(6)
            }
            max      = 100
            progress = item.percent
            progressDrawable = buildProgressDrawable(item.percent)
        }
        card.addView(pb)

        // Hàng 2: % dùng + hạn mức
        val tvDetail = TextView(ctx).apply {
            text = "${item.percent}% · hạn mức ${item.limitMB} MB/ngày"
            textSize = 11f
            setTextColor(0xFF757575.toInt())
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(tvDetail)

        return card
    }

    private fun buildProgressDrawable(percent: Int): android.graphics.drawable.Drawable {
        val color = when {
            percent >= 100 -> 0xFFD32F2F.toInt()
            percent >= 80  -> 0xFFF57C00.toInt()
            else           -> 0xFF1976D2.toInt()
        }
        val layer = android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.ColorDrawable(0xFFE0E0E0.toInt()),
            android.graphics.drawable.ClipDrawable(
                android.graphics.drawable.ColorDrawable(color),
                android.view.Gravity.START,
                android.graphics.drawable.ClipDrawable.HORIZONTAL
            )
        ))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.progress)
        return layer
    }
}
