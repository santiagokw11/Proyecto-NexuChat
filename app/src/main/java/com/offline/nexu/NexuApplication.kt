package com.offline.nexu

import android.app.Application
import android.content.Intent
import android.os.Build
import com.offline.nexu.data.local.Prefs
import com.offline.nexu.ui.home.P2PManager

// P2PManager.context solo se asignaba en ChatActivity.onCreate: si el proceso recibía un
// paquete (grupo, archivo, perfil) sin haber abierto nunca un chat individual en esa sesión,
// todo lo que depende de ese context (GroupManager, guardado de archivos recibidos) se
// descartaba en silencio. Fijarlo aquí garantiza que exista desde el arranque del proceso.
class NexuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        P2PManager.context = applicationContext

        // Antes el servicio solo arrancaba al abrir la pantalla Radar, así que si el proceso
        // se reiniciaba en segundo plano (p. ej. tras un reinicio del sistema) nunca volvía a
        // conectarse por sí solo. Arrancarlo aquí, si ya hay una cuenta registrada, hace que
        // la malla P2P exista desde el arranque del proceso, no solo con Home abierta.
        if (Prefs(this).isRegistered()) {
            try {
                val serviceIntent = Intent(this, ConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {}
        }
    }
}
