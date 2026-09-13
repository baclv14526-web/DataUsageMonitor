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
        if (!granted) toast("Cần quyền thông báo để nhận cảnh báo data")
    }

    private val readPhoneStateLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) toast("✅ Đã cấp quyền đọc thông tin SIM")
        else toast("⚠️ Chưa cấp quyền đọc SIM — đo data có thể kém chính xác")
        refreshStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        NotificationHelper.createChannels(this)
        requestNotifPermIfNeeded()
        requestReadPhoneStateIfNeeded()
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
        b.btnHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        b.btnDebug.setOnClickListener {
            startActivity(Intent(this, DebugActivity::class.java))
        }

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
        val hasPhone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        b.tvUsageStatus.text = if (hasUsage)
            "✅ Đã cấp quyền Truy cập sử dụng"
        else
            "❌ Chưa cấp quyền Truy cập sử dụng (bắt buộc)"

        b.tvBatteryStatus.text = if (isBatteryOptIgnored())
            "✅ Đã bỏ qua tối ưu hóa pin"
        else
            "⚠️ Chưa bỏ qua tối ưu hóa pin (khuyến nghị)"

        // Cập nhật trạng thái SIM permission
        b.tvSimStatus.text = if (hasPhone)
            "✅ Đã cấp quyền đọc thông tin SIM"
        else
            "⚠️ Chưa cấp quyền đọc SIM (cần cho máy 2 SIM)"

        if (hasUsage) {
            val used = DataUsageUtils.getMobileDataUsageBytes(
                this, Prefs.startOfTodayMillis(), System.currentTimeMillis())
            b.tvCurrentUsage.text = when {
                used < 0  -> "📶 Không đọc được dữ liệu"
                used == 0L -> "📶 Data hôm nay: 0 B (bấm Debug nếu bất thường)"
                else      -> "📶 Data di động hôm nay: ${DataUsageUtils.formatBytes(used)}"
            }
        } else {
            b.tvCurrentUsage.text = "📶 Chưa thể đọc (cần cấp quyền)"
        }

        b.switchMonitoring.isChecked = Prefs.isMonitoringEnabled(this)
    }

    private fun saveLimit() {
        val mb = b.etLimit.text?.toString()?.toLongOrNull()
        when {
            mb == null || mb <= 0 -> toast("Nhập hạn mức hợp lệ (số MB > 0)")
            mb > 102_400          -> toast("Hạn mức tối đa 102400 MB (100 GB)")
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
        ) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestReadPhoneStateIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) readPhoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
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
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("Vào Cài đặt > Pin > tắt tối ưu cho app này")
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
