package com.verifyblind.mobile.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.verifyblind.mobile.MainActivity
import com.verifyblind.mobile.R
import com.verifyblind.mobile.util.SecureStore

class VBMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        SecureStore.saveFcmToken(applicationContext, token)
        android.util.Log.d("FCM_TOKEN", token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.data["title"] ?: message.notification?.title ?: return
        val body = message.data["body"] ?: message.notification?.body ?: return

        ensureNotificationChannel()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val notifIconColor = if (isNight) Color.parseColor("#0D47A1") else Color.parseColor("#000068")
        val titleColor = if (isNight) Color.WHITE else Color.parseColor("#FF111111")
        val bodyColor  = if (isNight) Color.parseColor("#FFCCCCCC") else Color.parseColor("#FF555555")

        val remoteViews = RemoteViews(packageName, R.layout.notification_custom).apply {
            setImageViewBitmap(R.id.iv_notif_icon, buildCircularIcon())
            setTextViewText(R.id.tv_notif_title, title)
            setTextViewText(R.id.tv_notif_body, body)
            setTextColor(R.id.tv_notif_title, titleColor)
            setTextColor(R.id.tv_notif_body, bodyColor)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(notifIconColor)
            .setContentTitle(title)
            .setContentText(body)
            .setCustomContentView(remoteViews)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun buildCircularIcon(): Bitmap {
        val src = BitmapFactory.decodeResource(resources, R.drawable.ic_notification_large)
            ?: return BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
        val size = (resources.displayMetrics.density * 44).toInt()
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        if (src !== scaled) src.recycle()

        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        scaled.recycle()
        return output
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fcm_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.fcm_channel_desc)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "verifyblind_notifications"
    }
}
