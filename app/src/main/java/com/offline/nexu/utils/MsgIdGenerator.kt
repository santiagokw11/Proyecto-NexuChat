package com.offline.nexu.utils

import java.util.concurrent.atomic.AtomicLong

// System.currentTimeMillis() solo repetía valor entre llamadas rápidas (doble-tap en enviar,
// bucles de SYS:GROUP_CREATE/UPDATE con offsets fijos) y entre mensajes de chats distintos,
// lo que con msgId como identificador de mensaje causaba colisiones silenciosas.
// Esta función garantiza valores estrictamente crecientes y nunca repetidos en este proceso.
object MsgIdGenerator {
    private val lastId = AtomicLong(0L)

    fun next(): Long {
        while (true) {
            val now = System.currentTimeMillis()
            val prev = lastId.get()
            val candidate = if (now > prev) now else prev + 1
            if (lastId.compareAndSet(prev, candidate)) return candidate
        }
    }
}
