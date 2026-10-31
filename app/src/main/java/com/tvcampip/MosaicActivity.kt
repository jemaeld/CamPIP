package com.tvcampip

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView

/**
 * Pantalla principal: muestra en vivo todas las cámaras activas en un mosaico que se adapta al
 * número de cámaras. La cámara marcada con ★ PiP es la que queda en Picture-in-Picture al salir.
 */
class MosaicActivity : AppCompatActivity() {

    private class Tile(
        val camera: Camera,
        val view: View,
        val label: TextView,
        val badge: TextView,
        val player: CameraPlayer,
    ) {
        var aspect: Rational? = null
    }

    private lateinit var store: CameraStore
    private lateinit var mosaic: MosaicLayout
    private lateinit var topBar: View
    private lateinit var empty: View

    private val tiles = mutableListOf<Tile>()
    private var pipTile: Tile? = null
    private var allowAutoPip = true
    private var goHomeAfterPip = false
    private val handler = Handler(Looper.getMainLooper())

    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mosaic)

        store = CameraStore(this)
        mosaic = findViewById(R.id.mosaic)
        topBar = findViewById(R.id.topBar)
        empty = findViewById(R.id.empty)

        topBar.setOnClickListener { showMenu() }
        topBar.setOnFocusChangeListener { v, focused ->
            if (focused) showMenuButton()
            v.animate().scaleX(if (focused) 1.3f else 1f).scaleY(if (focused) 1.3f else 1f).setDuration(120).start()
        }
        showMenuButton()

        // Flecha atrás: siempre deja la cámara elegida en PiP / ventana flotante, sin pulsar el botón.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val noOption = !StreamPolicy.pipSupported(this@MosaicActivity) &&
                        !StreamPolicy.floatingPreferred(this@MosaicActivity)
                    if (pipTile == null || isInPictureInPictureMode || noOption) {
                        // Nada que mostrar flotando: la flecha atrás cierra la app como siempre.
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    } else {
                        leaveToBackground(goHome = true)
                    }
                }
            },
        )

        // Al abrir la app (una vez por proceso) se busca una versión nueva y se pregunta si actualizar.
        if (!updateChecked) {
            updateChecked = true
            UpdateFlow(this).check(manual = false)
        }
    }

    override fun onStart() {
        super.onStart()
        if (tiles.isEmpty()) rebuild()
    }

    override fun onResume() {
        super.onResume()
        allowAutoPip = true
        showMenuButton()
        Floating.stop(this) // al volver a la app se cierra la ventana flotante, como haría el PiP
        updatePipParams()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) clearTiles()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        clearTiles()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- mosaico

    private fun rebuild() {
        clearTiles()
        val cameras = store.load().filter { it.enabled }
        val wantedId = settings.getString(PREF_PIP_ID, null)
        val inflater = LayoutInflater.from(this)

        for (cam in cameras) {
            val v = inflater.inflate(R.layout.item_tile, mosaic, false)
            val label = v.findViewById<TextView>(R.id.label)
            val badge = v.findViewById<TextView>(R.id.badge)
            val cp = CameraPlayer(
                this,
                cam.gridUrl ?: cam.url, // el mosaico usa el secundario; si no hay, el principal
                v.findViewById<PlayerView>(R.id.playerView),
                v.findViewById<TextView>(R.id.status),
            )
            label.text = cam.name
            v.foreground = ContextCompat.getDrawable(this, R.drawable.tile_focus)

            val tile = Tile(cam, v, label, badge, cp)
            cp.onVideoSize = { w, h ->
                val ratio = (w.toFloat() / h).coerceIn(0.5f, 2.39f)
                tile.aspect = Rational((ratio * 1000).toInt(), 1000)
                if (tile === pipTile) updatePipParams()
            }
            v.setOnClickListener { selectPip(tile) }
            v.setOnLongClickListener {
                openFull(tile)
                true
            }
            mosaic.addView(v)
            tiles.add(tile)
        }

        pipTile = tiles.firstOrNull { it.camera.id == wantedId } ?: tiles.firstOrNull()
        refreshBadges()
        empty.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
        tiles.forEach { it.player.start() }
        pipTile?.view?.requestFocus()
        updatePipParams()
    }

    private fun clearTiles() {
        tiles.forEach { it.player.release() }
        tiles.clear()
        pipTile = null
        mosaic.removeAllViews()
    }

    private fun refreshBadges() {
        tiles.forEach { it.badge.visibility = if (it === pipTile) View.VISIBLE else View.GONE }
    }

    private fun selectPip(tile: Tile) {
        pipTile = tile
        settings.edit().putString(PREF_PIP_ID, tile.camera.id).apply()
        refreshBadges()
        updatePipParams()
        Toast.makeText(this, "En PiP quedará: ${tile.camera.name}", Toast.LENGTH_SHORT).show()
    }

    private fun openFull(tile: Tile) {
        allowAutoPip = false
        updatePipParams()
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_NAME, tile.camera.name)
                .putExtra(PlayerActivity.EXTRA_URL, StreamPolicy.fullUrl(this, tile.camera))
                .putExtra(PlayerActivity.EXTRA_AUTO_PIP, false),
        )
    }

    // ---------------------------------------------------------------- menú hamburguesa

    private val hideMenuRunnable = Runnable {
        if (!topBar.hasFocus()) topBar.animate().alpha(0f).setDuration(300).start()
    }

    /** Muestra el botón ☰ y lo oculta de nuevo a los 5 segundos sin actividad. */
    private fun showMenuButton() {
        if (isInPictureInPictureMode) return
        topBar.animate().cancel()
        topBar.alpha = 0.85f
        handler.removeCallbacks(hideMenuRunnable)
        handler.postDelayed(hideMenuRunnable, 5000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isInPictureInPictureMode) {
            showMenuButton()
            var focused = currentFocus
            val isNav = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            // Sin foco (el mosaico se acaba de reconstruir): el primer botón de flecha solo lo recupera.
            if (focused == null && isNav) {
                (pipTile?.view ?: tiles.firstOrNull()?.view)?.requestFocus()
                focused = currentFocus
                if (focused != null) return true
            }
            val tile = tiles.firstOrNull { it.view === focused }
            when (event.keyCode) {
                // Borde derecho (o fila de arriba con "arriba"): un movimiento más selecciona el botón ☰.
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (tile != null) {
                    val f = tile.view
                    val hasRight = tiles.any {
                        it !== tile && it.view.left >= f.right - 1 && it.view.top < f.bottom && it.view.bottom > f.top
                    }
                    if (!hasRight) {
                        topBar.requestFocus()
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (tile != null) {
                    val f = tile.view
                    val hasAbove = tiles.any {
                        it !== tile && it.view.bottom <= f.top + 1 && it.view.left < f.right && it.view.right > f.left
                    }
                    if (!hasAbove && f.right >= (mosaic.width * 0.5f)) {
                        topBar.requestFocus()
                        return true
                    }
                }
                // Desde el ☰, izquierda o abajo regresan al mosaico.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_DOWN -> if (focused === topBar) {
                    (pipTile?.view ?: tiles.firstOrNull()?.view)?.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMenu() {
        val pipLabel = if (StreamPolicy.pipSupported(this)) "Enviar a PiP" else "Enviar a ventana flotante"
        val items = arrayOf(pipLabel, "Gestionar cámaras", "Salir (cierra la app y la ventana)")
        AlertDialog.Builder(this)
            .setTitle("Menú")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> leaveToBackground(goHome = true)
                    1 -> openManage()
                    2 -> exitApp()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Cierra la app y la ventana flotante sin dejar nada encima (la flecha atrás e Inicio sí las dejan). */
    private fun exitApp() {
        allowAutoPip = false
        updatePipParams()
        Floating.stop(this)
        finishAndRemoveTask()
    }

    private fun openManage() {
        allowAutoPip = false
        updatePipParams()
        startActivity(Intent(this, ManageActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && !isInPictureInPictureMode) {
            showMenu()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- Picture-in-Picture

    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(pipTile?.aspect ?: Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: entra solo a PiP al pulsar Inicio.
            builder.setAutoEnterEnabled(allowAutoPip && pipTile != null)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
            // sin soporte de PiP en este dispositivo
        }
    }

    /**
     * Manda la cámara elegida a PiP o, en equipos sin PiP, a la ventana flotante.
     * Devuelve true si la app quedó en segundo plano con la cámara visible.
     */
    private fun leaveToBackground(goHome: Boolean): Boolean {
        val tile = pipTile
        if (tile == null) {
            Toast.makeText(this, "No hay cámaras visibles para enviar a PiP", Toast.LENGTH_LONG).show()
            return false
        }
        if (StreamPolicy.pipSupported(this)) return enterPip(goHome)
        if (!StreamPolicy.floatingPreferred(this)) {
            Toast.makeText(
                this,
                "Este equipo no soporta PiP. Activa «Ventana flotante» en Gestionar cámaras",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }
        if (!Floating.ensurePermission(this)) return false
        // En la ventana pequeña siempre se usa el stream ligero.
        if (!Floating.start(this, tile.camera.name, StreamPolicy.lightUrl(tile.camera))) {
            Toast.makeText(this, "No se pudo abrir la ventana flotante", Toast.LENGTH_LONG).show()
            return false
        }
        if (goHome) showHomeScreen()
        return true
    }

    private fun showHomeScreen() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun enterPip(goHome: Boolean): Boolean {
        if (isInPictureInPictureMode) return true
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
        return ok
    }

    /** Al salir con Inicio: PiP a mano antes de Android 12, o ventana flotante si no hay PiP. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!allowAutoPip || pipTile == null) return
        if (!StreamPolicy.pipSupported(this)) {
            if (StreamPolicy.floatingPreferred(this)) leaveToBackground(goHome = false)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            enterPip(goHome = false)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            showPipLayout(true)
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
            // La ventana PiP se cerró con la X: terminar y liberar todos los streams.
            finishAndRemoveTask()
        } else {
            showPipLayout(false)
        }
    }

    private fun switchStream(tile: Tile, url: String) {
        if (tile.player.url != url) {
            tile.player.url = url
            tile.player.start()
        }
    }

    /** En PiP solo se muestra la cámara elegida; las demás se detienen para ahorrar ancho de banda. */
    private fun showPipLayout(on: Boolean) {
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        for (t in tiles) {
            // En PiP la cámara elegida usa el stream principal; al volver al mosaico, el secundario.
            if (t === pipTile) switchStream(t, if (on) t.camera.url else (t.camera.gridUrl ?: t.camera.url))
            t.view.foreground = if (on) null else ContextCompat.getDrawable(this, R.drawable.tile_focus)
            if (on) {
                t.label.visibility = View.GONE
                t.badge.visibility = View.GONE
                if (t !== pipTile) {
                    t.player.release()
                    t.view.visibility = View.GONE
                }
            } else {
                t.view.visibility = View.VISIBLE
                t.label.visibility = View.VISIBLE
                if (t !== pipTile) t.player.start()
            }
        }
        if (!on) {
            showMenuButton()
            refreshBadges()
            pipTile?.view?.requestFocus()
        }
    }

    companion object {
        const val PREF_PIP_ID = "pip_camera_id"

        @Volatile
        private var updateChecked = false
    }
}
