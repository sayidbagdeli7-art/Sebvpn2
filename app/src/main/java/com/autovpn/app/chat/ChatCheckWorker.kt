package com.autovpn.app.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autovpn.app.MainActivity
import com.autovpn.app.subscription.ChatSeenStore

class ChatCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val CHANNEL_ID = "chat_new_message"
        const val NOTIF_ID = 42
    }

    override suspend fun doWork(): Result {
        return try {
            val messages = ChatRepository.fetchMessages()
            val seenCount = ChatSeenStore.load(applicationContext)
            if (messages.size > seenCount) {
                showNotification(messages.size - seenCount)
            }
            // Don't mark as "seen" here - only actually opening the chat tab does
            // that, so the badge/notification logic stays accurate even if the
            // user dismisses the notification without reading.
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun showNotification(newCount: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "پیام‌های جدیدِ چت", NotificationManager.IMPORTANCE_DEFAULT)
            nm.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            applicationContext, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("پیام جدید در چت")
            .setContentText(if (newCount == 1) "یه پیامِ جدید داری" else "$newCount پیامِ جدید داری")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify(NOTIF_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted - silently skip.
        }
    }
}
