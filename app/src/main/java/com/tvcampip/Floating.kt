package com.tvcampip

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Ayudantes para la ventana flotante (respaldo cuando el equipo no soporta PiP). */
object Floating {

    fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Abre la pantalla del permiso "Mostrar sobre otras apps"; en algunos TV no existe y se indica el comando adb. */
    fun requestPermission(activity: Activity) {
        try {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")),
            )
            Toast.makeText(activity, "Activa «Mostrar sobre otras apps» para TV Cam PiP y vuelve", Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                activity,
                "Este TV no tiene esa pantalla. Por adb: adb shell appops set ${activity.packageName} SYSTEM_ALERT_WINDOW allow",
                Toast.LENGTH_LONG,
            ).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "No se pudo abrir el permiso de ventana flotante", Toast.LENGTH_LONG).show()
        }
    }

    /** Verifica el permiso; si falta, lo solicita. Devuelve true si ya se puede mostrar. */
    fun ensurePermission(activity: Activity): Boolean {
        if (canDraw(activity)) return true
        requestPermission(activity)
        return false
    }

    /** Arranca la ventana flotante con la cámara indicada. Devuelve false si no se pudo. */
    fun start(context: Context, name: String, url: String): Boolean {
        if (!canDraw(context)) return false
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_NAME, name)
                    .putExtra(OverlayService.EXTRA_URL, url),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (_: Exception) {
        }
    }
}
