package com.datamonitor.app

import android.app.NotificationManager
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.datamonitor.app.databinding.ActivityAlertBinding

/**
 * Màn hình cảnh báo toàn màn hình, hiển thị được cả khi máy đang khóa,
 * dùng khi lưu lượng data vượt hạn mức hàng ngày.
 */
class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        keyguardManager.requestDismissKeyguard(this, null)

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val usedBytes = intent.getLongExtra("usedBytes", 0L)
        val limitMB = intent.getLongExtra("limitMB", 0L)

        binding.tvMessage.text =
            "Bạn đã dùng ${DataUsageUtils.formatMB(usedBytes)} / $limitMB MB data hôm nay.\n" +
            "Đã vượt hạn mức đã đặt!"

        binding.btnDismiss.setOnClickListener {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NotificationHelper.NOTIF_ID_CRITICAL)
            finish()
        }
    }
}
