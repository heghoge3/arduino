package com.vabugeode.app.achievements

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vabugeode.app.MainActivity
import com.vabugeode.app.R

object AchievementNotifications {
    fun post(context:Context,event:AchievementEvent) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("achievements","Достижения походов",NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null,null);enableVibration(false);description="Открытия по данным выбранных походов"
        })
        val open=PendingIntent.getActivity(context,72,Intent(context,MainActivity::class.java).putExtra("achievements",true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body=event.unlocked.take(4).joinToString("\n"){it.title} + if(event.unlocked.size>4)"\nЕщё: ${event.unlocked.size-4}" else ""
        val notification=NotificationCompat.Builder(context,"achievements")
            .setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(if(event.unlocked.size==1)"Достижение открыто" else "Новые достижения: ${event.unlocked.size}")
            .setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setSilent(true).build()
        try {manager.notify(7200,notification)}catch(_:SecurityException){ /* Открытия остаются на экране и в базе. */ }
    }
}
