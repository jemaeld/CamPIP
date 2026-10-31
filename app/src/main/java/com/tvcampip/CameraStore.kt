package com.tvcampip

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
)

/** Guarda la lista de cámaras como JSON en SharedPreferences. */
class CameraStore(context: Context) {

    private val prefs = context.getSharedPreferences("cameras", Context.MODE_PRIVATE)

    fun load(): MutableList<Camera> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        val result = mutableListOf<Camera>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                result.add(Camera(o.getString("id"), o.getString("name"), o.getString("url")))
            }
        } catch (_: Exception) {
            // JSON corrupto: se ignora y se empieza con lista vacía
        }
        return result
    }

    fun save(cameras: List<Camera>) {
        val arr = JSONArray()
        cameras.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val KEY = "list"
    }
}
