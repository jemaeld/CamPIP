package com.tvcampip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    /** Si está activa, la cámara aparece en el mosaico principal. */
    val enabled: Boolean = true,
    /** Stream secundario (menor resolución) para el mosaico. Si es null, el mosaico usa [url]. */
    val gridUrl: String? = null,
)

/**
 * Guarda la lista de cámaras como JSON en SharedPreferences.
 * Es seguro usarlo desde varios hilos (la app y el servidor web local lo comparten).
 */
class CameraStore(context: Context) {

    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): MutableList<Camera> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val result = mutableListOf<Camera>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(
                    Camera(
                        id = o.getString("id"),
                        name = o.getString("name"),
                        url = o.getString("url"),
                        enabled = o.optBoolean("enabled", true), // datos de versiones anteriores no lo traen
                        gridUrl = if (o.isNull("gridUrl")) null else o.optString("gridUrl").ifBlank { null },
                    ),
                )
            }
        } catch (_: Exception) {
            // JSON corrupto: se ignora y se empieza con lista vacía
        }
        return result
    }

    @Synchronized
    fun add(camera: Camera) {
        val list = load()
        list.add(camera)
        save(list)
    }

    @Synchronized
    fun update(camera: Camera) {
        val list = load()
        val i = list.indexOfFirst { it.id == camera.id }
        if (i >= 0) {
            list[i] = camera
            save(list)
        }
    }

    @Synchronized
    fun delete(id: String) {
        val list = load()
        if (list.removeAll { it.id == id }) save(list)
    }

    private fun save(cameras: List<Camera>) {
        val arr = JSONArray()
        cameras.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("url", it.url).put("enabled", it.enabled)
                    .put("gridUrl", it.gridUrl ?: JSONObject.NULL),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
