package com.verifyblind.mobile.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.os.Build
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(buildLargeIcon())
            .setColor(ContextCompat.getColor(this, R.color.sv_secondary))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    // Mavi daire üzerine beyaz kalkan — Telegram gibi renkli ikon
    private fun buildLargeIcon(): Bitmap {
        val size = (resources.displayMetrics.density * 48).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(this@VBMessagingService, R.color.sv_secondary)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, circlePaint)

        val shield = BitmapFactory.decodeResource(resources, R.drawable.ic_notification)
        val pad = size * 0.15f
        canvas.drawBitmap(shield, null, RectF(pad, pad, size - pad, size - pad), Paint(Paint.ANTI_ALIAS_FLAG))
        shield.recycle()

        return bmp
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VerifyBlind Bildirimleri",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "VerifyBlind uygulama bildirimleri"
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "verifyblind_notifications"
    }
}
