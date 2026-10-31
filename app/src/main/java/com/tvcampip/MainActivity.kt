package com.tvcampip

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var store: CameraStore
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var autoPip: CheckBox
    private var cameras = mutableListOf<Camera>()
    private var webServer: WebServer? = null

    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = CameraStore(this)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        autoPip = findViewById(R.id.autoPip)

        adapter = ArrayAdapter(this, R.layout.item_camera, R.id.itemText, mutableListOf<String>())
        list.adapter = adapter

        autoPip.isChecked = settings.getBoolean(PREF_AUTO_PIP, true)
        autoPip.setOnCheckedChangeListener { _, checked ->
            settings.edit().putBoolean(PREF_AUTO_PIP, checked).apply()
        }

        list.setOnItemClickListener { _, _, position, _ ->
            play(cameras[position], autoPip.isChecked)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            showOptions(cameras[position])
            true
        }

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditDialog(null) }
        findViewById<Button>(R.id.btnDiscover).setOnClickListener { startDiscovery() }
        findViewById<Button>(R.id.btnPhone).setOnClickListener { showPhoneDialog() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        webServer?.stop()
        webServer = null
        super.onDestroy()
    }

    /** La tecla Menú del control abre las opciones de la cámara seleccionada. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus()) {
            val pos = list.selectedItemPosition
            if (pos in cameras.indices) {
                showOptions(cameras[pos])
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun alive() = !isFinishing && !isDestroyed

    private fun refresh() {
        cameras = store.load()
        adapter.clear()
        adapter.addAll(cameras.map { it.name })
        adapter.notifyDataSetChanged()
        val hasCameras = cameras.isNotEmpty()
        list.visibility = if (hasCameras) View.VISIBLE else View.GONE
        empty.visibility = if (hasCameras) View.GONE else View.VISIBLE
        if (hasCameras) list.requestFocus()
    }

    private fun play(camera: Camera, pip: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_NAME, camera.name)
            .putExtra(PlayerActivity.EXTRA_URL, camera.url)
            .putExtra(PlayerActivity.EXTRA_AUTO_PIP, pip)
        startActivity(intent)
    }

    // ---------------------------------------------------------------- opciones de una cámara

    private fun showOptions(camera: Camera) {
        val options = arrayOf("Ver en pantalla completa", "Ver en PiP", "Editar", "Eliminar")
        AlertDialog.Builder(this)
            .setTitle(camera.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> play(camera, false)
                    1 -> play(camera, true)
                    2 -> showEditDialog(camera)
                    3 -> confirmDelete(camera)
                }
            }
            .show()
    }

    private fun confirmDelete(camera: Camera) {
        AlertDialog.Builder(this)
            .setMessage("¿Eliminar «${camera.name}»?")
            .setPositiveButton("Eliminar") { _, _ ->
                store.delete(camera.id)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- alta manual

    private fun verticalBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), 0)
    }

    private fun textInput(hint: String, text: String = "", uri: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        setText(text)
        if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
    }

    private fun passwordInput(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    private fun showEditDialog(existing: Camera?, presetName: String = "", presetUrl: String = "rtsp://") {
        val box = verticalBox()
        val nameInput = textInput("Nombre (ej. Entrada)", existing?.name ?: presetName)
        val urlInput = textInput(
            "rtsp://usuario:clave@192.168.1.50:554/stream1",
            existing?.url ?: presetUrl,
            uri = true,
        )
        box.addView(nameInput)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Agregar cámara" else "Editar cámara")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || !(url.startsWith("rtsp://") || url.startsWith("rtsps://"))) {
                    toast("Pon un nombre y una URL que empiece con rtsp://")
                    return@setPositiveButton
                }
                if (existing == null) {
                    store.add(Camera(name = name, url = url))
                } else {
                    store.update(existing.copy(name = name, url = url))
                }
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- descubrimiento ONVIF

    private fun progressDialog(message: String): AlertDialog =
        AlertDialog.Builder(this).setMessage(message).setCancelable(false).create().also { it.show() }

    private fun startDiscovery() {
        val progress = progressDialog("Buscando cámaras ONVIF en la red…")
        Thread {
            val found = try {
                OnvifDiscovery.discover(applicationContext, 4000)
            } catch (_: Exception) {
                emptyList()
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                progress.dismiss()
                if (found.isEmpty()) showNoneFound() else showFound(found)
            }
        }.start()
    }

    private fun showFound(found: List<DiscoveredCamera>) {
        val labels = found.map { "${it.name}  (${it.host})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Cámaras encontradas")
            .setItems(labels) { _, which ->
                val cam = found[which]
                askCredentials(cam.name, cam.deviceUrl, cam.host)
            }
            .setNeutralButton("Por IP…") { _, _ -> showByIpDialog() }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showNoneFound() {
        AlertDialog.Builder(this)
            .setTitle("No se encontraron cámaras")
            .setMessage(
                "Puede que ONVIF esté desactivado en la cámara o que esté en otra red. " +
                    "Puedes consultarla por su IP o agregarla con su URL RTSP.",
            )
            .setPositiveButton("Por IP (ONVIF)") { _, _ -> showByIpDialog() }
            .setNeutralButton("Agregar manual") { _, _ -> showEditDialog(null) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showByIpDialog() {
        val box = verticalBox()
        val nameInput = textInput("Nombre (opcional)")
        val hostInput = textInput("IP de la cámara (ej. 192.168.1.50)")
        val portInput = textInput("Puerto ONVIF", "80")
        box.addView(nameInput)
        box.addView(hostInput)
        box.addView(portInput)

        AlertDialog.Builder(this)
            .setTitle("Agregar por IP (ONVIF)")
            .setView(box)
            .setPositiveButton("Siguiente") { _, _ ->
                val host = hostInput.text.toString().trim()
                val port = portInput.text.toString().trim().ifEmpty { "80" }
                if (!Regex("[A-Za-z0-9.\\-]+").matches(host) || !Regex("\\d{1,5}").matches(port)) {
                    toast("Revisa la IP y el puerto")
                    return@setPositiveButton
                }
                val name = nameInput.text.toString().trim().ifEmpty { host }
                askCredentials(name, "http://$host:$port/onvif/device_service", host)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askCredentials(name: String, deviceUrl: String, host: String) {
        val box = verticalBox()
        val userInput = textInput("Usuario", "admin")
        val passInput = passwordInput("Contraseña")
        box.addView(userInput)
        box.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Acceso a $name")
            .setView(box)
            .setPositiveButton("Conectar") { _, _ ->
                fetchStreams(name, deviceUrl, host, userInput.text.toString().trim(), passInput.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun fetchStreams(name: String, deviceUrl: String, host: String, user: String, pass: String) {
        val progress = progressDialog("Consultando la cámara…")
        Thread {
            val result = try {
                Result.success(OnvifClient(deviceUrl, user, pass).fetchStreams())
            } catch (e: Exception) {
                Result.failure<List<OnvifClient.Stream>>(e)
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                progress.dismiss()
                result.fold(
                    onSuccess = { showStreams(name, it) },
                    onFailure = { showFetchError(name, deviceUrl, host, user, pass, it) },
                )
            }
        }.start()
    }

    private fun showStreams(name: String, streams: List<OnvifClient.Stream>) {
        val labels = streams.map { it.label }.toTypedArray()
        val checked = BooleanArray(streams.size) { it == 0 }
        AlertDialog.Builder(this)
            .setTitle("Streams de $name")
            .setMultiChoiceItems(labels, checked) { _, i, isChecked -> checked[i] = isChecked }
            .setPositiveButton("Agregar") { _, _ ->
                val selected = streams.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) return@setPositiveButton
                selected.forEach { s ->
                    val camName = if (streams.size > 1) "$name - ${s.label}" else name
                    store.add(Camera(name = camName, url = s.url))
                }
                refresh()
                toast("Cámaras agregadas: ${selected.size}")
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFetchError(name: String, deviceUrl: String, host: String, user: String, pass: String, error: Throwable) {
        val auth = (error as? OnvifException)?.auth == true
        AlertDialog.Builder(this)
            .setTitle("No se pudo obtener el stream")
            .setMessage(
                (error.message ?: "Error de conexión") +
                    "\n\nPuedes reintentar o agregarla manualmente con su URL RTSP.",
            )
            .setPositiveButton(if (auth) "Reintentar" else "Agregar manual") { _, _ ->
                if (auth) {
                    askCredentials(name, deviceUrl, host)
                } else {
                    showEditDialog(null, name, guessRtspUrl(host, user, pass))
                }
            }
            .setNeutralButton(if (auth) "Agregar manual" else "Reintentar") { _, _ ->
                if (auth) {
                    showEditDialog(null, name, guessRtspUrl(host, user, pass))
                } else {
                    askCredentials(name, deviceUrl, host)
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun guessRtspUrl(host: String, user: String, pass: String): String {
        val cred = if (user.isNotEmpty()) "${android.net.Uri.encode(user)}:${android.net.Uri.encode(pass)}@" else ""
        return "rtsp://$cred$host:554/"
    }

    // ---------------------------------------------------------------- servidor web + QR

    private fun showPhoneDialog() {
        val ip = NetUtil.localIpv4()
        if (ip == null) {
            toast("No se detectó la red local. Conecta el TV al WiFi o Ethernet.")
            return
        }
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val token = (1..8).map { chars.random() }.joinToString("")

        webServer?.stop()
        val server = WebServer(store, token)
        try {
            server.start()
        } catch (e: Exception) {
            toast("No se pudo iniciar el servidor: ${e.message}")
            return
        }
        webServer = server

        val url = "http://$ip:${server.port}/?k=$token"

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }
        val qr = ImageView(this).apply {
            setImageBitmap(NetUtil.qrBitmap(url))
            setBackgroundColor(android.graphics.Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
        }
        val text = TextView(this).apply {
            this.text = "Escanea el código con tu celular (misma red) o abre:\n$url\n\n" +
                "Cierra esta ventana para detener el servidor."
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        box.addView(qr)
        box.addView(text)

        AlertDialog.Builder(this)
            .setTitle("Agregar cámaras desde el celular")
            .setView(box)
            .setPositiveButton("Listo", null)
            .setOnDismissListener {
                server.stop()
                if (webServer === server) webServer = null
                refresh()
            }
            .show()
    }

    private companion object {
        const val PREF_AUTO_PIP = "auto_pip"
    }
}
