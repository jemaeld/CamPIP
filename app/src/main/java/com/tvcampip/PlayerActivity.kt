package com.tvcampip

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Reproduce un stream RTSP y puede mandarse a Picture-in-Picture, de modo que la cámara
 * quede siempre encima mientras se usa otra app (YouTube, etc.).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var overlay: View
    private lateinit var btnPip: Button

    private var player: ExoPlayer? = null
    private var cameraName = ""
    private var cameraUrl = ""
    private var pendingAutoPip = false
    private var goHomeAfterPip = false
    private var videoAspect = Rational(16, 9)

    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { startPlayback() }
    private val hideOverlayRunnable = Runnable { overlay.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.playerView)
        status = findViewById(R.id.status)
        overlay = findViewById(R.id.overlay)
        btnPip = findViewById(R.id.btnPip)

        btnPip.setOnClickListener { enterPip(goHome = true) }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        readIntent(intent)
        updatePipParams()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
        startPlayback()
    }

    private fun readIntent(i: Intent) {
        cameraName = i.getStringExtra(EXTRA_NAME) ?: ""
        cameraUrl = i.getStringExtra(EXTRA_URL) ?: ""
        pendingAutoPip = i.getBooleanExtra(EXTRA_AUTO_PIP, false)
    }

    override fun onStart() {
        super.onStart()
        if (player == null) startPlayback()
    }

    override fun onResume() {
        super.onResume()
        if (pendingAutoPip) {
            pendingAutoPip = false
            // Pequeña espera para que la actividad termine de mostrarse antes de entrar a PiP.
            handler.postDelayed({ enterPip(goHome = true) }, 600)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) releasePlayer()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- reproducción

    private fun startPlayback() {
        releasePlayer()
        if (cameraUrl.isEmpty()) {
            showStatus("Sin URL de cámara")
            return
        }
        showStatus("Conectando a $cameraName…")

        // Buffers pequeños para tener la menor latencia posible en vivo.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 250, 500)
            .build()

        val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        playerView.player = exo

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // TCP es más estable en WiFi que UDP
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(cameraUrl)))

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> status.visibility = View.GONE
                    Player.STATE_BUFFERING -> showStatus("Cargando…")
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showStatus("Sin señal. Reintentando…")
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, 3000)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = (videoSize.width.toFloat() / videoSize.height).coerceIn(0.5f, 2.39f)
                    videoAspect = Rational((ratio * 1000).toInt(), 1000)
                    updatePipParams()
                }
            }
        })

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    private fun releasePlayer() {
        handler.removeCallbacks(retryRunnable)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- Picture-in-Picture

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(videoAspect)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: entra solo a PiP al pulsar Inicio.
            builder.setAutoEnterEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
            // El dispositivo no soporta PiP o no está permitido; se ignora.
        }
    }

    private fun enterPip(goHome: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "Este dispositivo no soporta Picture-in-Picture", Toast.LENGTH_LONG).show()
            return
        }
        if (isInPictureInPictureMode) return
        goHomeAfterPip = goHome
        val ok = try {
            enterPictureInPictureMode(pipParams())
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            goHomeAfterPip = false
            Toast.makeText(
                this,
                "No se pudo entrar a PiP. Actívalo en Ajustes > Apps > Acceso especial > Imagen en imagen",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Antes de Android 12 hay que entrar a PiP a mano cuando el usuario sale de la app. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player != null) {
            enterPip(goHome = false)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            handler.removeCallbacks(hideOverlayRunnable)
            overlay.visibility = View.GONE
            if (goHomeAfterPip) {
                goHomeAfterPip = false
                // Deja al usuario en el launcher para abrir YouTube u otra app.
                handler.postDelayed({
                    startActivity(
                        Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }, 300)
            }
        } else if (lifecycle.currentState == Lifecycle.State.CREATED) {
            // La ventana PiP se cerró con la X: terminar y liberar el stream.
            finishAndRemoveTask()
        }
    }

    // ---------------------------------------------------------------- control remoto

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val wake = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (wake && overlay.visibility != View.VISIBLE && !isInPictureInPictureMode) {
            showOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun showOverlay() {
        overlay.visibility = View.VISIBLE
        btnPip.requestFocus()
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, 6000)
    }

    companion object {
        const val EXTRA_NAME = "camera_name"
        const val EXTRA_URL = "camera_url"
        const val EXTRA_AUTO_PIP = "auto_pip"
    }
}
