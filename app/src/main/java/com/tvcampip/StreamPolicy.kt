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

    fun lightUrl(camera: Camera): String = camera.gridUrl ?: camera.url

    /** URL para pantalla completa: en equipos sin PiP siempre el stream ligero si existe. */
    fun fullUrl(context: Context, camera: Camera): String =
        if (pipSupported(context)) camera.url else lightUrl(camera)
}
