package com.offline.nexu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.data.network.NexuMeshManager

class ConnectionService : Service() {

    private val CHANNEL_ID = "NexuConnectionService"

    // Instancia única y persistente: antes este servicio solo mostraba una notificación y no
    // hacía nada de red, así que la app dejaba de recibir mensajes/grupos en cuanto el usuario
    // salía de la pantalla Radar. Ahora mantiene su propio ciclo de advertising/discovery para
    // seguir formando parte de la malla aunque ninguna Activity esté visible.
    private var meshManager: NexuMeshManager? = null

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

        if (meshManager == null) {
            val profile = Prefs(this).getUserProfile()
            if (profile.id.isNotEmpty()) {
                meshManager = NexuMeshManager(applicationContext, profile.name)
                meshManager?.iniciarModoWalkieTalkie()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // No llamamos P2PManager.clearSession() aquí: eso es para un logout explícito
        // (ver P2PManager.cerrarSesionCompleta). Si el sistema mata y reinicia este servicio
        // por memoria, no debe desconectar ni borrar la sesión activa del usuario.
        meshManager = null
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