package com.tvcampip

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView

/**
 * Un reproductor RTSP de baja latencia ligado a un PlayerView, con reintento automático.
 * Se usa una instancia por cada mosaico.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CameraPlayer(
    private val context: Context,
    private val url: String,
    private val playerView: PlayerView,
    private val status: TextView,
    private val muted: Boolean = true,
) {
    /** Se llama con el ancho y alto reales del video cuando se conocen. */
    var onVideoSize: ((Int, Int) -> Unit)? = null

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { start() }

    fun start() {
        release()
        showStatus("Conectando…")

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 3000, 250, 500)
            .build()
        // Con varias cámaras a la vez se pueden agotar los decodificadores por hardware;
        // el fallback permite usar uno por software en lugar de fallar.
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build()
        exo.volume = if (muted) 0f else 1f
        playerView.player = exo

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(10_000)
            .createMediaSource(MediaItem.fromUri(Uri.parse(url)))

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
                    onVideoSize?.invoke(videoSize.width, videoSize.height)
                }
            }
        })

        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
        player = exo
    }

    fun release() {
        handler.removeCallbacks(retryRunnable)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }
}
