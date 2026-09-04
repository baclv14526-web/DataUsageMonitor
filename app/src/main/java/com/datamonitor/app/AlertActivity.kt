package com.datamonitor.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.datamonitor.app.databinding.ActivityAlertBinding

class AlertActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hiện trên màn hình khóa
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        km.requestDismissKeyguard(this, null)

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val usedBytes = intent.getLongExtra("usedBytes", 0L)
        val limitMB   = intent.getLongExtra("limitMB", 0L)

        binding.tvMessage.text =
            "Bạn đã dùng ${DataUsageUtils.formatBytes(usedBytes)}\n" +
            "vượt hạn mức $limitMB MB hôm nay!"

        binding.btnDismiss.setOnClickListener {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NotificationHelper.ID_CRITICAL)
            finish()
        }
    }
}
