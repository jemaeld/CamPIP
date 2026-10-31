package com.tvcampip

import android.content.Context
import android.content.pm.PackageManager

/**
 * Decide qué hacer según las capacidades del equipo:
 * - Con PiP del sistema: se usa PiP y el stream principal.
 * - Sin PiP: opcionalmente se usa la ventana flotante propia y siempre el stream ligero (secundario).
 */
object StreamPolicy {
    const val PREF_FLOATING = "floating_window"

    fun pipSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Preferencia del usuario. Por defecto está activa solo en equipos que no soportan PiP. */
    fun floatingPreferred(context: Context): Boolean =
        prefs(context).getBoolean(PREF_FLOATING, !pipSupported(context))

    fun setFloatingPreferred(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(PREF_FLOATING, on).apply()
    }

    /** True si, al salir de la app, hay que usar la ventana flotante en lugar de PiP. */
    fun useFloating(context: Context): Boolean = !pipSupported(context) && floatingPreferred(context)

    const val PREF_FLOAT_SIZE = "floating_size"
    val SIZE_NAMES = arrayOf("Pequeña", "Mediana", "Grande")
    private val SIZE_FRACTIONS = floatArrayOf(0.20f, 0.28f, 0.40f)

    /** 0 = pequeña, 1 = mediana (por defecto), 2 = grande. */
    fun floatingSize(context: Context): Int =
        prefs(context).getInt(PREF_FLOAT_SIZE, 1).coerceIn(0, SIZE_NAMES.size - 1)

    fun setFloatingSize(context: Context, index: Int) {
        prefs(context).edit().putInt(PREF_FLOAT_SIZE, index).apply()
    }

    /** Fracción del ancho de pantalla que ocupa la ventana flotante. */
    fun floatingWidthFraction(context: Context): Float = SIZE_FRACTIONS[floatingSize(context)]

    fun lightUrl(camera: Camera): String = camera.gridUrl ?: camera.url

    /** URL para pantalla completa: en equipos sin PiP siempre el stream ligero si existe. */
    fun fullUrl(context: Context, camera: Camera): String =
        if (pipSupported(context)) camera.url else lightUrl(camera)
}
