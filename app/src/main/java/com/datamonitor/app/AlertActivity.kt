package com.datamonitor.app

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.datamonitor.app.databinding.ActivityAlertBinding

class AlertActivity : AppCompatActivity() {

    private lateinit var b: ActivityAlertBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Hiện trên màn hình khóa (API 27+)
        // Với API < 27 dùng flag window cũ (đã deprecated nhưng vẫn hoạt động)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Thử dismiss keyguard — có thể fail nếu có PIN/pattern → bỏ qua
        try {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, null)
            }
        } catch (_: Exception) {}

        b = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Đọc data từ intent an toàn — dùng getLongExtra với default hợp lệ
        val usedBytes = intent?.getLongExtra("usedBytes", -1L) ?: -1L
        val limitMB   = intent?.getLongExtra("limitMB", 0L) ?: 0L

        b.tvMessage.text = if (usedBytes >= 0)
            "Bạn đã dùng ${DataUsageUtils.formatBytes(usedBytes)}\nvượt hạn mức $limitMB MB hôm nay!"
        else
            "Bạn đã vượt hạn mức $limitMB MB data hôm nay!"

        b.btnDismiss.setOnClickListener {
            try {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .cancel(NotificationHelper.ID_CRITICAL)
            } catch (_: Exception) {}
            finish()
        }
    }
}
