package com.tvcampip

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat

/** Ayudantes para la ventana flotante (respaldo cuando el equipo no soporta PiP). */
object Floating {

    /**
     * Algunos firmware de TV devuelven false en canDrawOverlays aunque el usuario ya lo autorizó,
     * por eso también se consulta directamente el appop.
     */
    fun canDraw(context: Context): Boolean {
        if (Settings.canDrawOverlays(context)) return true
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow("android:system_alert_window", Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

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

    /**
     * Verifica el permiso. Si parece faltar, abre la pantalla de ajustes UNA sola vez; después
     * intenta mostrar la ventana de todos modos (el servicio avisa si realmente falla),
     * para no entrar en un bucle de solicitudes si el sistema reporta mal el permiso.
     */
    fun ensurePermission(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (canDraw(activity)) {
            prefs.edit().putBoolean("overlay_asked", false).apply()
            return true
        }
        if (prefs.getBoolean("overlay_asked", false)) return true
        prefs.edit().putBoolean("overlay_asked", true).apply()
        requestPermission(activity)
        return false
    }

    /** Arranca la ventana flotante con la cámara indicada. Devuelve false si no se pudo. */
    fun start(context: Context, name: String, url: String): Boolean {
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
