package com.ninthsoft.ime.base.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import splitties.systemservices.notificationManager

fun createNotificationChannel(
    id: String,
    name: String,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel =
            NotificationChannel(
                id,
                name,
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = id }
        notificationManager.createNotificationChannel(channel)
    }
}
