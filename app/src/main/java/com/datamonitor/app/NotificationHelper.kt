package com.datamonitor.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    const val CHANNEL_ONGOING = "channel_ongoing"
    const val CHANNEL_WARNING = "channel_warning"
    const val CHANNEL_CRITICAL = "channel_critical"

    const val NOTIF_ID_ONGOING = 1001
    const val NOTIF_ID_WARNING = 1002
    const val NOTIF_ID_CRITICAL = 1003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val ongoing = NotificationChannel(
            CHANNEL_ONGOING, "Giám sát data (nền)", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Thông báo nền hiển thị lưu lượng data đã dùng hôm nay"
            setShowBadge(false)
        }

        val warning = NotificationChannel(
            CHANNEL_WARNING, "Cảnh báo sắp hết hạn mức", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khi dùng gần hết hạn mức data hàng ngày"
            enableVibration(true)
        }

        val critical = NotificationChannel(
            CHANNEL_CRITICAL, "Vượt hạn mức data", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Cảnh báo khẩn cấp khi vượt hạn mức data hàng ngày"
            enableVibration(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            setBypassDnd(true)
        }

        nm.createNotificationChannel(ongoing)
        nm.createNotificationChannel(warning)
        nm.createNotificationChannel(critical)
    }

    fun buildOngoingNotification(
        context: Context, usedBytes: Long, limitMB: Long
    ): android.app.Notification {
        val usedText = DataUsageUtils.formatMB(usedBytes)
        val percent = if (limitMB > 0)
            ((DataUsageUtils.bytesToMB(usedBytes) / limitMB) * 100).toInt() else 0

        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Data 3G/4G/5G hôm nay: $usedText")
            .setContentText("Đã dùng $percent% hạn mức ${limitMB} MB/ngày")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun sendWarningNotification(context: Context, percent: Int, usedBytes: Long, limitMB: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openAppIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 1, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_WARNING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ Sắp hết hạn mức data")
            .setContentText("Đã dùng $percent% (${DataUsageUtils.formatMB(usedBytes)} / $limitMB MB) hôm nay")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        nm.notify(NOTIF_ID_WARNING, notif)
    }

    fun sendCriticalNotification(context: Context, usedBytes: Long, limitMB: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Intent mở màn hình cảnh báo toàn màn hình (hiện cả khi khóa máy)
        val fullScreenIntent = Intent(context, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("usedBytes", usedBytes)
            putExtra("limitMB", limitMB)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 2, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_CRITICAL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("🚫 Đã vượt hạn mức data hôm nay!")
            .setContentText("Đã dùng ${DataUsageUtils.formatMB(usedBytes)} / $limitMB MB")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_CRITICAL, notif)
    }
}
