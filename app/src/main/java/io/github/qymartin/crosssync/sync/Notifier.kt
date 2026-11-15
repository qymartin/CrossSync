package io.github.qymartin.crosssync.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.qymartin.crosssync.R
import io.github.qymartin.crosssync.ui.MainActivity

object Notifier {
    private const val CH_RESULT = "sync_result"
    private const val CH_ALERT = "sync_alert"
    private const val ID_RESULT = 1
    private const val ID_ALERT = 2

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "同步结果", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "同步失败提醒", NotificationManager.IMPORTANCE_HIGH))
    }

    fun result(context: Context, text: String) = show(context, CH_RESULT, ID_RESULT, "CrossSync 同步完成", text, false)

    fun alert(context: Context, title: String, text: String) = show(context, CH_ALERT, ID_ALERT, title, text, true)

    @android.annotation.SuppressLint("MissingPermission")
    private fun show(context: Context, channel: String, id: Int, title: String, text: String, high: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
