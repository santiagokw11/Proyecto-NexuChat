package com.offline.nexu.ui.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Pinto las líneas de mi radar de un color azul cyan (#00E6FE).
    private val paintLine = Paint().apply {
        color = Color.parseColor("#00E6FE")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true // Para que las líneas no se vean pixeladas.
    }
    
    // Esto es para la animación del pulso expansivo del radar. 
    // Le doy el mismo color azul, pero con un "44" al inicio para hacerlo casi transparente.
    private val paintScan = Paint().apply {
        color = Color.parseColor("#4400E6FE") 
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    // Configuro cómo se verán los nombres de los usuarios (letras blancas de 35px).
    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 35f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }
    
    // Y configuro el pincel para los Emojis (mucho más grandes, de 70px).
    private val paintEmoji = Paint().apply {
        textSize = 70f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var pulseRadius = 0f // Esta variable la iré cambiando para animar el radar.

    // Una estructura de datos rápida para saber dónde dibujar a cada persona.
    data class UserPoint(val name: String, val emoji: String, val x: Float, val y: Float)
    private val users = mutableMapOf<String, UserPoint>()

    // Evento que dispararé si alguien toca a un usuario en pantalla.
    var onUserClick: ((String, String) -> Unit)? = null

    fun addUser(endpointId: String, name: String, emoji: String) {
        // Si el usuario ya está en mi radar, no hago nada.
        if (users.containsKey(endpointId)) return
        
        // Si es nuevo, le calculo un ángulo y una distancia aleatoria para ponerlo en el mapa.
        val angle = Random.nextDouble(0.0, 2 * Math.PI)
        val distance = Random.nextDouble(radius * 0.3, radius * 0.8) // Entre el 30% y 80% del borde.
        
        // Con trigonometría básica convierto ángulo y distancia a X,Y.
        val px = centerX + (distance * Math.cos(angle)).toFloat()
        val py = centerY + (distance * Math.sin(angle)).toFloat()
        
        users[endpointId] = UserPoint(name, emoji, px, py)
    }

    fun removeUser(endpointId: String) {
        // Alguien se fue, lo borro del mapa.
        users.remove(endpointId)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Android me avisa de qué tamaño quedó mi vista, así calculo mi centro y mi radio máximo.
        centerX = w / 2f
        centerY = h / 2f
        radius = Math.min(centerX, centerY) - 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Dibujo tres anillos concéntricos que siempre se quedan quietos.
        canvas.drawCircle(centerX, centerY, radius * 0.33f, paintLine)
        canvas.drawCircle(centerX, centerY, radius * 0.66f, paintLine)
        canvas.drawCircle(centerX, centerY, radius, paintLine)

        // 2. Animo el pulso expansivo del radar.
        pulseRadius += 3f // En cada "frame", agrando el pulso 3 píxeles.
        if (pulseRadius > radius) {
            pulseRadius = 0f // Si llegó al borde, lo devuelvo al centro.
        }
        canvas.drawCircle(centerX, centerY, pulseRadius, paintLine)
        canvas.drawCircle(centerX, centerY, pulseRadius * 0.5f, paintScan)

        // 3. Pinto los emojis y nombres de todos los usuarios que he detectado.
        for ((_, user) in users) {
            canvas.drawText(user.emoji, user.x, user.y, paintEmoji)
            // Bajo un poquito (50px) para escribir el nombre sin tapar el emoji.
            canvas.drawText(user.name, user.x, user.y + 50f, paintText)
        }

        // 4. Le ordeno a Android que redibuje esta vista INMEDIATAMENTE.
        // Esto crea un bucle infinito ("Game Loop") que hace posible la animación del pulso.
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Alguien tocó la pantalla.
        if (event.action == MotionEvent.ACTION_DOWN) {
            val ex = event.x
            val ey = event.y
            
            // Reviso la distancia entre el dedo y cada uno de los usuarios del radar.
            for ((id, user) in users) {
                // Pitágoras al rescate.
                val distance = sqrt((ex - user.x).pow(2) + (ey - user.y).pow(2))
                if (distance < 90f) {
                    // Si el toque fue cerca (menos de 90px), asumo que le atinó y disparo el click.
                    onUserClick?.invoke(id, user.name)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}