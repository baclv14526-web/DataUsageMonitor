package com.datamonitor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.datamonitor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    // registerForActivityResult phải gọi trước onCreate → khai báo ở đây
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted)
            toast("Cần quyền thông báo để nhận cảnh báo data")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NotificationHelper.createChannels(this)
        requestNotifPermIfNeeded()
        setupUI()
    }

    private fun setupUI() {
        b.etLimit.setText(Prefs.getDailyLimitMB(this).toString())
        b.switchMonitoring.isChecked = Prefs.isMonitoringEnabled(this)

        b.btnSave.setOnClickListener { saveLimit() }

        b.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        b.btnBattery.setOnClickListener { requestBatteryOptimization() }

        // Dùng flag để tránh vòng lặp: switch thay đổi → listener gọi →
        // code cập nhật switch → listener lại gọi
        var switching = false
        b.switchMonitoring.setOnCheckedChangeListener { _, on ->
            if (switching) return@setOnCheckedChangeListener
            if (on) {
                if (!DataUsageUtils.hasUsageAccessPermission(this)) {
                    switching = true
                    b.switchMonitoring.isChecked = false
                    switching = false
                    toast("Vui lòng cấp quyền 'Truy cập sử dụng' trước!")
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } else {
                    startMonitorService()
                }
            } else {
                stopMonitorService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasUsage = DataUsageUtils.hasUsageAccessPermission(this)

        b.tvUsageStatus.text = if (hasUsage)
            "✅ Đã cấp quyền Truy cập sử dụng"
        else
            "❌ Chưa cấp quyền Truy cập sử dụng (bắt buộc)"

        b.tvBatteryStatus.text = if (isBatteryOptIgnored())
            "✅ Đã bỏ qua tối ưu hóa pin"
        else
            "⚠️ Chưa bỏ qua tối ưu hóa pin (khuyến nghị)"

        // Đọc data trên main thread chỉ khi có quyền và trong onResume
        // (không gây ANR vì chỉ đọc 1 lần, không loop)
        if (hasUsage) {
            val used = DataUsageUtils.getMobileDataUsageBytes(
                this, Prefs.startOfTodayMillis(), System.currentTimeMillis())
            b.tvCurrentUsage.text = when {
                used < 0  -> "📶 Không đọc được dữ liệu"
                else      -> "📶 Data di động hôm nay: ${DataUsageUtils.formatBytes(used)}"
            }
        } else {
            b.tvCurrentUsage.text = "📶 Chưa thể đọc (cần cấp quyền)"
        }

        // Đồng bộ lại trạng thái switch với thực tế (tránh lệch khi service
        // bị kill bởi hệ thống)
        b.switchMonitoring.isChecked = Prefs.isMonitoringEnabled(this)
    }

    private fun saveLimit() {
        val mb = b.etLimit.text?.toString()?.toLongOrNull()
        when {
            mb == null || mb <= 0 ->
                toast("Nhập hạn mức hợp lệ (số MB > 0)")
            mb > 102_400 ->     // > 100 GB: có thể nhập nhầm
                toast("Hạn mức tối đa là 102400 MB (100 GB)")
            else -> {
                Prefs.setDailyLimitMB(this, mb)
                toast("Đã lưu: ${DataUsageUtils.formatBytes(mb * 1_048_576L)}/ngày")
            }
        }
    }

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBatteryOptimization() {
        if (isBatteryOptIgnored()) {
            toast("Đã bỏ qua tối ưu hóa pin rồi ✅")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            // Một số ROM tùy biến không có màn hình này → mở trang pin tổng quát
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Vui lòng tắt tối ưu hóa pin cho app trong Cài đặt > Pin")
            }
        }
    }

    private fun isBatteryOptIgnored(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun startMonitorService() {
        val svc = Intent(this, DataUsageMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svc)
        else
            startService(svc)
        toast("✅ Đã bật giám sát data")
    }

    private fun stopMonitorService() {
        startService(Intent(this, DataUsageMonitorService::class.java).apply {
            action = DataUsageMonitorService.ACTION_STOP
        })
        toast("Đã tắt giám sát")
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
