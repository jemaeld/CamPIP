package com.tvcampip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat

/** Recibe el resultado de la instalación y abre la confirmación del sistema cuando hace falta. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // El sistema reemplaza la app; hay que volver a abrirla desde el launcher.
            }
            else -> {
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error desconocido"
                Toast.makeText(context, "No se pudo instalar la actualización: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val ACTION = "com.tvcampip.INSTALL_RESULT"
    }
}
