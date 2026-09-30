package com.offline.nexu.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import com.offline.nexu.data.local.Prefs

object ThemeUtils {
    fun getAuraColor(context: Context): Int {
        val hex = Prefs(context).getUserProfile().colorHex
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            Color.parseColor("#00BCD4") // Cyan por defecto en caso de error
        }
    }

    fun getBottomNavColorStateList(auraColor: Int): ColorStateList {
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(
            auraColor,
            Color.parseColor("#888888") // Gris para íconos inactivos
        )
        return ColorStateList(states, colors)
    }
}