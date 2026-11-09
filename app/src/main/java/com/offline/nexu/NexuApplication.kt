package com.offline.nexu

import android.app.Application
import com.offline.nexu.ui.home.P2PManager

// P2PManager.context solo se asignaba en ChatActivity.onCreate: si el proceso recibía un
// paquete (grupo, archivo, perfil) sin haber abierto nunca un chat individual en esa sesión,
// todo lo que depende de ese context (GroupManager, guardado de archivos recibidos) se
// descartaba en silencio. Fijarlo aquí garantiza que exista desde el arranque del proceso.
class NexuApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        P2PManager.context = applicationContext
    }
}
