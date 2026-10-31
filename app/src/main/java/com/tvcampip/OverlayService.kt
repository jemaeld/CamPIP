package com.tvcampip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.media3.ui.PlayerView

/**
 * Ventana flotante con una cámara, para equipos sin Picture-in-Picture del sistema.
 * Se dibuja encima de otras apps (permiso "Mostrar sobre otras apps"), abajo a la derecha.
 */
class OverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var root: View? = null
    private var camPlayer: CameraPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_NAME) ?: ""
        val url = intent?.getStringExtra(EXTRA_URL) ?: ""

        // Un servicio en primer plano debe llamar a startForeground enseguida.
        startForegroundCompat(name)

        if (intent?.action == ACTION_STOP || url.isEmpty() || !Floating.canDraw(this)) {
            removeWindow()
            stopSelf()
            return START_NOT_STICKY
        }
        removeWindow()
        showWindow(name, url)
        return START_NOT_STICKY
    }

    private fun showWindow(name: String, url: String) {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val themed = ContextThemeWrapper(this, R.style.Theme_TvCamPip)
        val view = LayoutInflater.from(themed).inflate(R.layout.view_overlay, null)
        view.findViewById<TextView>(R.id.label).text = name

        val metrics = resources.displayMetrics
        val width = maxOf((metrics.widthPixels * 0.28f).toInt(), (280 * metrics.density).toInt())
        val height = width * 9 / 16
        val margin = (24 * metrics.density).toInt()

        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin
        }

        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            stopSelf()
            return
        }
        windowManager = wm
        root = view

        // Ventana pequeña: siempre el stream que llegue (el ligero, elegido por quien llama).
        camPlayer = CameraPlayer(
            this,
            url,
            view.findViewById<PlayerView>(R.id.playerView),
            view.findViewById<TextView>(R.id.status),
        ).also { it.start() }
    }

    private fun removeWindow() {
        camPlayer?.release()
        camPlayer = null
        root?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
    }

    override fun onDestroy() {
        removeWindow()
        super.onDestroy()
    }

    private fun startForegroundCompat(name: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Ventana flotante", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MosaicActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("TV Cam PiP")
            .setContentText(if (name.isEmpty()) "Ventana flotante activa" else "Ventana flotante: $name")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Cerrar", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val EXTRA_NAME = "overlay_name"
        const val EXTRA_URL = "overlay_url"
        const val ACTION_STOP = "com.tvcampip.STOP_OVERLAY"
        private const val CHANNEL = "overlay"
        private const val NOTIF_ID = 42
    }
}
