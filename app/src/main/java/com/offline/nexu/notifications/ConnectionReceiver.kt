package com.offline.nexu.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.offline.nexu.data.network.NexuMeshManager

class ConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val endpointId = intent.getStringExtra("ENDPOINT_ID") ?: return
        val notificationId = intent.getIntExtra("NOTIF_ID", 0)

        when (action) {
            "ACCEPT_CONNECTION" -> {
                NexuMeshManager.aceptarConexionPendiente(context, endpointId)
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
            "REJECT_CONNECTION" -> {
                NexuMeshManager.rechazarConexionPendiente(context, endpointId)
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
        }
    }
}