package com.tvcampip

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Exportar e importar la lista de cámaras como archivo JSON. Lo usan la app y el servidor web.
 *
 * Formato:
 * { "app": "TvCamPip", "version": 1, "cameras": [
 *     { "name": "Entrada", "url": "rtsp://...", "gridUrl": "rtsp://..." | null, "enabled": true } ] }
 *
 * Al importar también se acepta una lista simple `[ {...}, {...} ]`.
 * OJO: las URLs pueden incluir usuario y contraseña, el archivo las contiene en texto plano.
 */
object CameraTransfer {

    const val FILE_NAME = "tvcampip-camaras.json"
    const val MAX_IMPORT_BYTES = 1_048_576

    class Parsed(val cameras: List<Camera>, val invalid: Int)

    fun export(cameras: List<Camera>): String {
        val arr = JSONArray()
        for (c in cameras) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("url", c.url)
                    .put("gridUrl", c.gridUrl ?: JSONObject.NULL)
                    .put("enabled", c.enabled),
            )
        }
        return JSONObject().put("app", "TvCamPip").put("version", 1).put("cameras", arr).toString(2)
    }

    /**
     * Valida el contenido sin tocar las cámaras guardadas.
     * Lanza [IllegalArgumentException] con un mensaje legible si el archivo no sirve.
     */
    fun parse(text: String): Parsed {
        val trimmed = text.trim().removePrefix("﻿").trim()
        if (trimmed.isEmpty()) throw IllegalArgumentException("El archivo está vacío")

        val arr = try {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed).getJSONArray("cameras")
        } catch (_: JSONException) {
            throw IllegalArgumentException("El archivo no tiene el formato esperado (JSON con una lista de cámaras)")
        }

        fun isRtsp(u: String) = u.startsWith("rtsp://") || u.startsWith("rtsps://")

        val result = mutableListOf<Camera>()
        var invalid = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
            if (o == null) {
                invalid++
                continue
            }
            val name = if (o.isNull("name")) "" else o.optString("name").trim()
            val url = if (o.isNull("url")) "" else o.optString("url").trim()
            val grid = if (o.isNull("gridUrl")) null else o.optString("gridUrl").trim().ifEmpty { null }
            if (name.isEmpty() || !isRtsp(url) || (grid != null && !isRtsp(grid))) {
                invalid++
                continue
            }
            result.add(Camera(name = name, url = url, enabled = o.optBoolean("enabled", true), gridUrl = grid))
        }
        if (result.isEmpty()) {
            throw IllegalArgumentException(
                if (invalid > 0) "Ninguna cámara del archivo es válida ($invalid con datos incorrectos)" else "El archivo no contiene cámaras",
            )
        }
        return Parsed(result, invalid)
    }

    fun summary(added: Int, skipped: Int, invalid: Int): String {
        val parts = mutableListOf("Importadas: $added")
        if (skipped > 0) parts.add("omitidas por repetidas: $skipped")
        if (invalid > 0) parts.add("inválidas: $invalid")
        return parts.joinToString(" · ")
    }
}
