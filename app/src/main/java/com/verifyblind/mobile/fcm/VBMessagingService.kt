package com.verifyblind.mobile.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
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
        val title = message.notification?.title ?: return
        val body = message.notification?.body ?: return

        ensureNotificationChannel()

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
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
