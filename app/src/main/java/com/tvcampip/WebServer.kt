package com.tvcampip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo (sin dependencias) para administrar las cámaras desde el navegador del celular.
 * Solo se mantiene encendido mientras el diálogo con el QR está abierto, y toda petición debe llevar
 * el token aleatorio que viaja en la URL del QR.
 */
class WebServer(
    private val store: CameraStore,
    private val token: String,
    private val preferredPort: Int = 8080,
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    var port: Int = preferredPort
        private set

    fun start() {
        val s = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: IOException) {
            ServerSocket(0)
        }
        serverSocket = s
        port = s.localPort
        running = true
        pool.execute {
            while (running) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (_: IOException) {
                    if (!running) break
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- HTTP

    private data class Resp(
        val code: Int,
        val body: String = "",
        val location: String? = null,
        val contentType: String = "text/html; charset=utf-8",
        val headers: Map<String, String> = emptyMap(),
    )

    private fun handle(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = 8000
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                var contentLength = 0
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                    }
                }
                val body = if (contentLength in 1..CameraTransfer.MAX_IMPORT_BYTES) readExactly(input, contentLength) else ByteArray(0)

                val method = parts[0].uppercase()
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = parseParams(target.substringAfter('?', ""))
                val form = if (method == "POST") parseParams(String(body, Charsets.UTF_8)) else emptyMap()

                val resp = if ((query["k"] ?: form["k"]) != token) {
                    Resp(403, page("Acceso denegado", "<p>Vuelve a escanear el código QR que aparece en el TV.</p>"))
                } else {
                    try {
                        route(method, path, query, form)
                    } catch (e: Exception) {
                        Resp(500, page("Error", "<p>${esc(e.message ?: "Error inesperado")}</p>"))
                    }
                }
                send(socket.getOutputStream(), resp)
            } catch (_: Exception) {
                // cliente desconectado o petición mal formada
            }
        }
    }

    private fun send(out: OutputStream, r: Resp) {
        val bytes = r.body.toByteArray(Charsets.UTF_8)
        val status = when (r.code) {
            200 -> "OK"
            303 -> "See Other"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 ${r.code} $status\r\n")
        sb.append("Content-Type: ${r.contentType}\r\n")
        for ((name, value) in r.headers) sb.append("$name: $value\r\n")
        sb.append("Content-Length: ${bytes.size}\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Connection: close\r\n")
        if (r.location != null) sb.append("Location: ${r.location}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16384) return null
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readExactly(input: BufferedInputStream, n: Int): ByteArray {
        val data = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(data, off, n - off)
            if (r < 0) break
            off += r
        }
        return data.copyOf(off)
    }

    private fun parseParams(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in s.split('&')) {
            if (pair.isEmpty()) continue
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    // ---------------------------------------------------------------- rutas

    private fun home(msg: String? = null): String {
        val base = "/?k=$token"
        return if (msg == null) base else base + "&msg=" + URLEncoder.encode(msg, "UTF-8")
    }

    private fun route(method: String, path: String, q: Map<String, String>, f: Map<String, String>): Resp {
        return when {
            method == "GET" && path == "/" -> Resp(200, indexPage(q["msg"]))

            method == "POST" && path == "/add" -> {
                val name = f["name"]?.trim().orEmpty()
                val url = f["url"]?.trim().orEmpty()
                val grid = f["grid"]?.trim().orEmpty().ifEmpty { null }
                fun isRtsp(u: String) = u.startsWith("rtsp://") || u.startsWith("rtsps://")
                if (name.isEmpty() || !isRtsp(url) || (grid != null && !isRtsp(grid))) {
                    Resp(200, page("Datos inválidos", "<p>Pon un nombre y URLs que empiecen con <code>rtsp://</code>.</p>${backLink()}"))
                } else {
                    store.add(Camera(name = name, url = url, gridUrl = grid))
                    Resp(303, location = home("Cámara agregada: $name"))
                }
            }

            method == "POST" && path == "/delete" -> {
                f["id"]?.let { store.delete(it) }
                Resp(303, location = home("Cámara eliminada"))
            }

            method == "GET" && path == "/export" -> Resp(
                200,
                CameraTransfer.export(store.load()),
                contentType = "application/json; charset=utf-8",
                headers = mapOf("Content-Disposition" to "attachment; filename=\"${CameraTransfer.FILE_NAME}\""),
            )

            method == "POST" && path == "/import" -> {
                val parsed = try {
                    CameraTransfer.parse(f["data"].orEmpty())
                } catch (e: IllegalArgumentException) {
                    return Resp(200, page("No se pudo importar", "<p>${esc(e.message ?: "Archivo no válido")}</p>${backLink()}"))
                }
                val (added, skipped) = store.importCameras(parsed.cameras, replace = f["replace"] == "1")
                Resp(303, location = home(CameraTransfer.summary(added, skipped, parsed.invalid)))
            }

            method == "POST" && path == "/toggle" -> {
                val cam = store.load().firstOrNull { it.id == f["id"] }
                if (cam != null) store.update(cam.copy(enabled = !cam.enabled))
                Resp(303, location = home(if (cam?.enabled == true) "Cámara oculta del mosaico" else "Cámara visible en el mosaico"))
            }

            method == "POST" && path == "/onvif" -> onvifLookup(f)

            else -> Resp(404, page("No encontrado", "<p>Ruta inválida.</p>${backLink()}"))
        }
    }

    private fun onvifLookup(f: Map<String, String>): Resp {
        val host = f["host"]?.trim().orEmpty()
        val port = f["port"]?.trim().takeUnless { it.isNullOrEmpty() } ?: "80"
        val user = f["user"].orEmpty()
        val pass = f["pass"].orEmpty()
        val name = f["name"]?.trim().takeUnless { it.isNullOrEmpty() } ?: host

        if (!Regex("[A-Za-z0-9.\\-]+").matches(host) || !Regex("\\d{1,5}").matches(port)) {
            return Resp(200, page("Datos inválidos", "<p>Revisa la IP y el puerto.</p>${backLink()}"))
        }

        val streams = try {
            OnvifClient("http://$host:$port/onvif/device_service", user, pass).fetchStreams()
        } catch (e: Exception) {
            return Resp(
                200,
                page(
                    "No se pudo consultar la cámara",
                    "<p>${esc(e.message ?: "Error de conexión")}</p>" +
                        "<p>Si ONVIF no funciona, agrégala con su URL RTSP en la primera sección.</p>${backLink()}",
                ),
            )
        }

        val choice = pickMainAndSub(streams)
        val sub = choice.sub
        val subText = if (sub != null) {
            "<b>Secundario (mosaico):</b> ${esc(sub.label)}<br><small>${esc(mask(sub.url))}</small>"
        } else {
            "<b>Secundario:</b> no encontrado, el mosaico usará el principal."
        }
        val content = """<form method="post" action="/add?k=$token" class="card">
<input type="hidden" name="k" value="$token">
<input type="hidden" name="name" value="${esc(name)}">
<input type="hidden" name="url" value="${esc(choice.main.url)}">
<input type="hidden" name="grid" value="${esc(sub?.url ?: "")}">
<p><b>Principal (pantalla completa y PiP):</b> ${esc(choice.main.label)}<br><small>${esc(mask(choice.main.url))}</small></p>
<p>$subText</p>
<button>Agregar «${esc(name)}»</button></form>"""
        return Resp(200, page("Streams encontrados", content + backLink()))
    }

    // ---------------------------------------------------------------- HTML

    private fun backLink() = """<p><a href="${home()}">&larr; Volver</a></p>"""

    private fun mask(url: String): String = url.replace(Regex("(://[^:/@]+:)[^@/]*@"), "$1•••@")

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(title: String, content: String): String = """<!doctype html>
<html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TV Cam PiP</title>
<style>
body{font-family:system-ui,sans-serif;background:#121212;color:#eee;margin:0;padding:16px;max-width:640px;margin:auto}
h1{font-size:22px} h2{font-size:18px;margin-top:28px}
.card{background:#1e1e1e;border-radius:10px;padding:12px;margin:10px 0}
.row{display:flex;justify-content:space-between;align-items:center;gap:8px;background:#1e1e1e;border-radius:10px;padding:10px 12px;margin:8px 0}
small{color:#9aa;word-break:break-all}
input{width:100%;box-sizing:border-box;padding:10px;margin:4px 0;border-radius:8px;border:1px solid #444;background:#2a2a2a;color:#fff;font-size:16px}
button{background:#1e88e5;color:#fff;border:0;border-radius:8px;padding:10px 16px;font-size:16px}
button.del{background:#c62828}
input[type=checkbox]{width:auto;margin-right:8px}
textarea{width:100%;box-sizing:border-box;padding:10px;margin:4px 0;border-radius:8px;border:1px solid #444;background:#2a2a2a;color:#fff;font-size:14px;font-family:monospace}
label{display:block;margin:8px 0}
.msg{background:#1b5e20;border-radius:8px;padding:10px;margin:10px 0}
a{color:#64b5f6} code{background:#2a2a2a;padding:2px 4px;border-radius:4px}
</style></head><body>
<h1>$title</h1>
$content
</body></html>"""

    private fun indexPage(msg: String?): String {
        val sb = StringBuilder()
        if (msg != null) sb.append("""<div class="msg">${esc(msg)}</div>""")

        sb.append("<h2>Cámaras</h2>")
        val cams = store.load()
        if (cams.isEmpty()) sb.append("<p><small>Aún no hay cámaras.</small></p>")
        for (c in cams) {
            val state = if (c.enabled) "" else " <small>(oculta)</small>"
            val gridLine = c.gridUrl?.let { "<br><small>mosaico: ${esc(mask(it))}</small>" } ?: ""
            val toggleLabel = if (c.enabled) "Ocultar" else "Mostrar"
            sb.append(
                """<div class="row">
<div><b>${esc(c.name)}</b>$state<br><small>${esc(mask(c.url))}</small>$gridLine</div>
<form method="post" action="/toggle?k=$token" style="margin:0">
<input type="hidden" name="k" value="$token">
<input type="hidden" name="id" value="${esc(c.id)}">
<button>$toggleLabel</button></form>
<form method="post" action="/delete?k=$token" style="margin:0" onsubmit="return confirm('¿Eliminar esta cámara?')">
<input type="hidden" name="k" value="$token">
<input type="hidden" name="id" value="${esc(c.id)}">
<button class="del">Eliminar</button></form></div>""",
            )
        }

        sb.append(
            """<h2>Agregar por URL RTSP</h2>
<form method="post" action="/add?k=$token" class="card">
<input type="hidden" name="k" value="$token">
<input name="name" placeholder="Nombre (ej. Entrada)" required>
<input name="url" placeholder="URL principal rtsp://usuario:clave@192.168.1.50:554/stream1" value="rtsp://" autocapitalize="none" autocorrect="off" required>
<input name="grid" placeholder="URL secundaria para el mosaico (opcional)" autocapitalize="none" autocorrect="off">
<button>Agregar</button></form>

<h2>Agregar por ONVIF (IP + usuario)</h2>
<p><small>La app consulta la cámara y obtiene la URL RTSP por ti.</small></p>
<form method="post" action="/onvif?k=$token" class="card">
<input type="hidden" name="k" value="$token">
<input name="name" placeholder="Nombre (opcional)">
<input name="host" placeholder="IP de la cámara (ej. 192.168.1.50)" inputmode="decimal" autocapitalize="none" required>
<input name="port" placeholder="Puerto ONVIF (80 por defecto)" inputmode="numeric">
<input name="user" placeholder="Usuario" autocapitalize="none" autocorrect="off">
<input name="pass" type="password" placeholder="Contraseña">
<button>Buscar streams</button></form>

<h2>Exportar e importar</h2>
<p><a href="/export?k=$token">Descargar todas las cámaras (.json)</a><br>
<small>El archivo incluye usuario y contraseña de las cámaras en texto plano: guárdalo en un lugar seguro.</small></p>
<form method="post" action="/import?k=$token" class="card">
<input type="hidden" name="k" value="$token">
<p><small>Elige el archivo .json o pega su contenido:</small></p>
<input type="file" id="importFile" accept=".json,application/json,text/plain">
<textarea name="data" id="importData" rows="6" placeholder="Contenido del archivo..."></textarea>
<label><input type="checkbox" name="replace" value="1" onchange="if(this.checked&&!confirm('Se borrarán las cámaras actuales y quedarán solo las del archivo. ¿Continuar?'))this.checked=false">Reemplazar todas las cámaras actuales (si no, se agregan a las existentes)</label>
<button>Importar</button></form>
<script>
document.getElementById('importFile').addEventListener('change', function (e) {
  var f = e.target.files[0];
  if (!f) return;
  var r = new FileReader();
  r.onload = function () { document.getElementById('importData').value = r.result; };
  r.readAsText(f);
});
</script>""",
        )
        return page("TV Cam PiP", sb.toString())
    }
}
