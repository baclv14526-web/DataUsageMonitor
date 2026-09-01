package com.datamonitor.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import com.datamonitor.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createChannels(this)

        binding.etLimit.setText(Prefs.getDailyLimitMB(this).toString())
        binding.switchMonitoring.isChecked = Prefs.isMonitoringEnabled(this)

        binding.btnSave.setOnClickListener {
            val text = binding.etLimit.text.toString()
            val mb = text.toLongOrNull()
            if (mb == null || mb <= 0) {
                Toast.makeText(this, "Vui lòng nhập hạn mức hợp lệ (MB)", Toast.LENGTH_SHORT).show()
            } else {
                Prefs.setDailyLimitMB(this, mb)
                Toast.makeText(this, "Đã lưu hạn mức: $mb MB/ngày", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnBatteryOptimization.setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        binding.switchMonitoring.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!DataUsageUtils.hasUsageAccessPermission(this)) {
                    Toast.makeText(
                        this,
                        "Vui lòng cấp quyền 'Truy cập sử dụng' trước khi bật giám sát",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.switchMonitoring.isChecked = false
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    return@setOnCheckedChangeListener
                }
                startMonitoringService()
            } else {
                stopMonitoringService()
            }
        }

        requestNotificationPermissionIfNeeded()
        updateStatusText()
    }

    override fun onResume() {
        super.onResume()
        updateStatusText()
    }

    private fun updateStatusText() {
        val hasUsage = DataUsageUtils.hasUsageAccessPermission(this)
        binding.tvUsageAccessStatus.text =
            if (hasUsage) "✅ Đã cấp quyền Truy cập sử dụng"
            else "❌ Chưa cấp quyền Truy cập sử dụng (bắt buộc)"

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(packageName)
        binding.tvBatteryStatus.text =
            if (ignoringBattery) "✅ Đã bỏ qua tối ưu hóa pin"
            else "⚠️ Chưa bỏ qua tối ưu hóa pin (khuyến nghị bật để chạy nền ổn định)"

        if (hasUsage) {
            val start = Prefs.startOfTodayMillis()
            val used = DataUsageUtils.getMobileDataUsageBytes(this, start, System.currentTimeMillis())
            if (used >= 0) {
                binding.tvCurrentUsage.text =
                    "Data 3G/4G/5G đã dùng hôm nay: ${DataUsageUtils.formatMB(used)}"
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Ứng dụng đã được bỏ qua tối ưu hóa pin", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startMonitoringService() {
        val intent = Intent(this, DataUsageMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Prefs.setMonitoringEnabled(this, true)
        Toast.makeText(this, "Đã bật giám sát lưu lượng data", Toast.LENGTH_SHORT).show()
    }

    private fun stopMonitoringService() {
        val intent = Intent(this, DataUsageMonitorService::class.java).apply {
            action = DataUsageMonitorService.ACTION_STOP
        }
        startService(intent)
        Prefs.setMonitoringEnabled(this, false)
        Toast.makeText(this, "Đã tắt giám sát", Toast.LENGTH_SHORT).show()
    }
}
