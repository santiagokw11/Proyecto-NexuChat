package com.offline.nexu.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.annotation.ColorInt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

class RadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paintScanFill = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val paintScanBorder = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val paintText = Paint().apply {
        color = Color.WHITE
        textSize = 35f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val paintEmoji = Paint().apply {
        textSize = 70f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f

    // Variables de animación
    private var pulseRadius = 0f

    var isAnimating = true // Control de animación

    data class UserPoint(val name: String, val emoji: String, val avatar: Bitmap?, val x: Float, val y: Float)
    private val users = mutableMapOf<String, UserPoint>()

    var onUserClick: ((String, String) -> Unit)? = null

    fun setAuraColor(@ColorInt colorInt: Int) {
        paintScanBorder.color = colorInt
        val r = Color.red(colorInt)
        val g = Color.green(colorInt)
        val b = Color.blue(colorInt)
        paintScanFill.color = Color.argb(64, r, g, b)
        invalidate()
    }

    fun addUser(endpointId: String, name: String, emoji: String, avatar: Bitmap? = null) {
        val existingKey = users.entries.find { it.value.name == name }?.key
        if (existingKey != null) {
            users.remove(existingKey)
        }
        if (users.containsKey(endpointId)) return

        var px = 0f
        var py = 0f
        var validPosition = false
        var attempts = 0

        val minDistance = radius * 0.45
        val maxDistance = radius * 0.85

        while (!validPosition && attempts < 50) {
            val angle = Random.nextDouble(0.0, 2 * Math.PI)
            val distance = Random.nextDouble(minDistance, maxDistance)
            px = centerX + (distance * Math.cos(angle)).toFloat()
            py = centerY + (distance * Math.sin(angle)).toFloat()

            validPosition = true
            for ((_, user) in users) {
                val distToOther = sqrt((px - user.x).pow(2) + (py - user.y).pow(2))
                if (distToOther < 140f) {
                    validPosition = false
                    break
                }
            }
            attempts++
        }

        users[endpointId] = UserPoint(name, emoji, avatar, px, py)
        invalidate()
    }

    fun removeUser(endpointId: String) {
        users.remove(endpointId)
        invalidate()
    }

    fun clearUsers() {
        users.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = Math.min(centerX, centerY) - 20f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val prefs = context.getSharedPreferences("NexuSettings", Context.MODE_PRIVATE)
        val style = prefs.getInt("radar_style", 0)

        if (isAnimating) {
            when (style) {
                0 -> {
                    // Clásico: Pulso Simple
                    pulseRadius += 4f
                    if (pulseRadius > radius) pulseRadius = 0f
                    canvas.drawCircle(centerX, centerY, pulseRadius, paintScanFill)
                    canvas.drawCircle(centerX, centerY, pulseRadius, paintScanBorder)
                }
                1 -> {
                    // Ondas Múltiples (Resonancia)
                    pulseRadius += 3f
                    if (pulseRadius > radius) pulseRadius = 0f

                    val pulseRadius2 = if (pulseRadius > radius / 3) pulseRadius - radius / 3 else 0f
                    val pulseRadius3 = if (pulseRadius > radius * 2 / 3) pulseRadius - radius * 2 / 3 else 0f

                    canvas.drawCircle(centerX, centerY, pulseRadius, paintScanBorder)
                    if (pulseRadius2 > 0) canvas.drawCircle(centerX, centerY, pulseRadius2, paintScanBorder)
                    if (pulseRadius3 > 0) canvas.drawCircle(centerX, centerY, pulseRadius3, paintScanBorder)
                }
                2 -> {
                    // Anillos Estáticos
                    canvas.drawCircle(centerX, centerY, radius * 0.33f, paintScanBorder)
                    canvas.drawCircle(centerX, centerY, radius * 0.66f, paintScanBorder)
                    canvas.drawCircle(centerX, centerY, radius, paintScanBorder)
                }
                3 -> {
                    // Minimalista
                    pulseRadius += 2f
                    if (pulseRadius > radius) pulseRadius = 0f
                    canvas.drawCircle(centerX, centerY, pulseRadius, paintScanBorder) // Sin fill para ser ligero
                }
            }
        }

        for ((_, user) in users) {
            if (user.avatar != null) {
                val imgRadius = user.avatar.width / 2f
                canvas.drawBitmap(user.avatar, user.x - imgRadius, user.y - imgRadius, null)
                canvas.drawText(user.name, user.x, user.y + imgRadius + 30f, paintText)
            } else {
                canvas.drawText(user.emoji, user.x, user.y, paintEmoji)
                canvas.drawText(user.name, user.x, user.y + 50f, paintText)
            }
        }

        if (isAnimating) {
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val ex = event.x
            val ey = event.y
            for ((id, user) in users) {
                val distance = sqrt((ex - user.x).pow(2) + (ey - user.y).pow(2))
                if (distance < 90f) {
                    onUserClick?.invoke(id, user.name)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }
}