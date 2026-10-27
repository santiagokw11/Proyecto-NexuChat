package com.offline.nexu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offline.nexu.ui.home.P2PManager

class ConnectionService : Service() {

    private val CHANNEL_ID = "NexuConnectionService"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NexuChat")
            .setContentText("Conexión P2P activa en segundo plano")
            .setSmallIcon(R.mipmap.ic_launcher) // Asegúrate de usar tu icono real
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        // Inicia el servicio en primer plano para que Android no lo mate
        startForeground(1, notification)

        // Aquí puedes inicializar tu lógica de P2PManager si no está iniciada
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Solo aquí se debe limpiar la sesión, cuando el servicio se detiene explícitamente
        P2PManager.clearSession()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Canal de Conexión Nexu",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}