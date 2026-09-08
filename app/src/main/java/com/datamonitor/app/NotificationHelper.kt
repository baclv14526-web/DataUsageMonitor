package com.datamonitor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CH_ONGOING  = "ch_ongoing"
    const val CH_WARNING  = "ch_warning"
    const val CH_CRITICAL = "ch_critical"

    const val ID_ONGOING  = 1001
    const val ID_WARNING  = 1002
    const val ID_CRITICAL = 1003

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = nm(ctx)
        // Kiểm tra đã tạo chưa để tránh gọi lặp nhiều lần
        if (nm.getNotificationChannel(CH_ONGOING) != null) return

        nm.createNotificationChannel(NotificationChannel(
            CH_ONGOING, "Giám sát data (nền)", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hiển thị lưu lượng data đã dùng hôm nay"
            setShowBadge(false)
            setSound(null, null)   // Không phát âm thanh cho notification nền
        })

        nm.createNotificationChannel(NotificationChannel(
            CH_WARNING, "Cảnh báo sắp hết hạn mức", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khi dùng ≥ 80% hạn mức"
            enableVibration(true)
        })

        nm.createNotificationChannel(NotificationChannel(
            CH_CRITICAL, "Đã vượt hạn mức data", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khẩn khi vượt 100% hạn mức"
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        })
    }

    fun buildOngoing(ctx: Context, usedBytes: Long, limitMB: Long): Notification {
        val pct = if (limitMB > 0 && usedBytes >= 0)
            ((DataUsageUtils.bytesToMB(usedBytes) / limitMB) * 100).toInt().coerceIn(0, 999)
        else 0

        val title = if (usedBytes < 0)
            "📶 Cần cấp quyền Truy cập sử dụng"
        else
            "📶 Data hôm nay: ${DataUsageUtils.formatBytes(usedBytes)}"

        val text = if (usedBytes < 0)
            "Nhấn để mở app và cấp quyền"
        else
            "Đã dùng $pct% · Hạn mức $limitMB MB/ngày"

        val pi = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(ctx, CH_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)  // Không rung/âm thanh khi update
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    fun sendWarning(ctx: Context, pct: Int, usedBytes: Long, limitMB: Long) {
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(ctx).notify(ID_WARNING,
            NotificationCompat.Builder(ctx, CH_WARNING)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚠️ Sắp hết hạn mức data ($pct%)")
                .setContentText(
                    "Đã dùng ${DataUsageUtils.formatBytes(usedBytes)} / $limitMB MB hôm nay")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }

    fun sendCritical(ctx: Context, usedBytes: Long, limitMB: Long) {
        // Full-screen intent: hiện màn hình cảnh báo kể cả khi máy khóa
        val alertIntent = Intent(ctx, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("usedBytes", usedBytes)
            putExtra("limitMB", limitMB)
        }
        // requestCode khác với các PI khác để tránh bị override
        val fullScreenPi = PendingIntent.getActivity(
            ctx, 99, alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm(ctx).notify(ID_CRITICAL,
            NotificationCompat.Builder(ctx, CH_CRITICAL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🚫 Đã vượt hạn mức data hôm nay!")
                .setContentText(
                    "Đã dùng ${DataUsageUtils.formatBytes(usedBytes)} / $limitMB MB")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPi, /* highPriority= */ true)
                .setContentIntent(fullScreenPi)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun nm(ctx: Context) =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
