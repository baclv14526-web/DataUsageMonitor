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

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted)
            Toast.makeText(this, "Cần quyền thông báo để nhận cảnh báo", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NotificationHelper.createChannels(this)
        requestNotifPermIfNeeded()

        b.etLimit.setText(Prefs.getDailyLimitMB(this).toString())
        b.switchMonitoring.isChecked = Prefs.isMonitoringEnabled(this)

        b.btnSave.setOnClickListener {
            val mb = b.etLimit.text.toString().toLongOrNull()
            if (mb == null || mb <= 0) {
                Toast.makeText(this, "Nhập hạn mức hợp lệ (số MB > 0)", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setDailyLimitMB(this, mb)
                Toast.makeText(this, "Đã lưu: $mb MB/ngày", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        b.btnBattery.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Toast.makeText(this, "Đã bỏ qua tối ưu pin rồi ✅", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }

        b.switchMonitoring.setOnCheckedChangeListener { _, on ->
            if (on) {
                if (!DataUsageUtils.hasUsageAccessPermission(this)) {
                    Toast.makeText(
                        this,
                        "Vui lòng cấp quyền 'Truy cập sử dụng' trước!",
                        Toast.LENGTH_LONG
                    ).show()
                    b.switchMonitoring.isChecked = false
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                startMonitorService()
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

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        b.tvBatteryStatus.text = if (pm.isIgnoringBatteryOptimizations(packageName))
            "✅ Đã bỏ qua tối ưu hóa pin"
        else
            "⚠️ Chưa bỏ qua tối ưu hóa pin (khuyến nghị)"

        if (hasUsage) {
            val used = DataUsageUtils.getMobileDataUsageBytes(
                this, Prefs.startOfTodayMillis(), System.currentTimeMillis()
            )
            b.tvCurrentUsage.text = if (used >= 0)
                "📶 Data di động hôm nay: ${DataUsageUtils.formatBytes(used)}"
            else
                "📶 Đang đọc dữ liệu…"
        } else {
            b.tvCurrentUsage.text = "📶 Chưa thể đọc (cần cấp quyền)"
        }
    }

    private fun requestNotifPermIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun startMonitorService() {
        val svc = Intent(this, DataUsageMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(svc)
        else
            startService(svc)
        Toast.makeText(this, "✅ Đã bật giám sát data", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitorService() {
        startService(Intent(this, DataUsageMonitorService::class.java).apply {
            action = DataUsageMonitorService.ACTION_STOP
        })
        Toast.makeText(this, "Đã tắt giám sát", Toast.LENGTH_SHORT).show()
    }
}
