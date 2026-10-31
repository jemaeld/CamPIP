package com.tvcampip

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.KeyEvent
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle

class MainActivity : AppCompatActivity() {

    private lateinit var store: CameraStore
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var autoPip: CheckBox
    private var cameras = mutableListOf<Camera>()

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
    }

    override fun onResume() {
        super.onResume()
        refresh()
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

    private fun refresh() {
        cameras = store.load()
        adapter.clear()
        adapter.addAll(cameras.map { it.name })
        adapter.notifyDataSetChanged()
        val hasCameras = cameras.isNotEmpty()
        list.visibility = if (hasCameras) android.view.View.VISIBLE else android.view.View.GONE
        empty.visibility = if (hasCameras) android.view.View.GONE else android.view.View.VISIBLE
        if (hasCameras) list.requestFocus()
    }

    private fun play(camera: Camera, pip: Boolean) {
        val intent = Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_NAME, camera.name)
            .putExtra(PlayerActivity.EXTRA_URL, camera.url)
            .putExtra(PlayerActivity.EXTRA_AUTO_PIP, pip)
        startActivity(intent)
    }

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
                cameras.removeAll { it.id == camera.id }
                store.save(cameras)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showEditDialog(existing: Camera?) {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Nombre (ej. Entrada)"
            setSingleLine()
            setText(existing?.name ?: "")
        }
        val urlInput = EditText(this).apply {
            hint = "rtsp://usuario:clave@192.168.1.50:554/stream1"
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url ?: "rtsp://")
        }
        box.addView(nameInput)
        box.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Agregar cámara" else "Editar cámara")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val name = nameInput.text.toString().trim()
                val url = urlInput.text.toString().trim()
                if (name.isEmpty() || !(url.startsWith("rtsp://") || url.startsWith("rtsps://"))) {
                    Toast.makeText(this, "Pon un nombre y una URL que empiece con rtsp://", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (existing == null) {
                    cameras.add(Camera(name = name, url = url))
                } else {
                    val i = cameras.indexOfFirst { it.id == existing.id }
                    if (i >= 0) cameras[i] = existing.copy(name = name, url = url)
                }
                store.save(cameras)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private companion object {
        const val PREF_AUTO_PIP = "auto_pip"
    }
}
