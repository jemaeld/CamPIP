package com.tvcampip

import android.net.Uri
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class OnvifException(message: String, val auth: Boolean = false) : Exception(message)

/**
 * Cliente ONVIF mínimo: obtiene las URLs RTSP de una cámara (GetCapabilities, GetProfiles,
 * GetStreamUri) usando autenticación WS-Security UsernameToken (PasswordDigest).
 * Bloquea la red: llamar desde un hilo en segundo plano.
 */
class OnvifClient(
    private val deviceUrl: String,
    private val user: String,
    private val pass: String,
) {
    data class Stream(val label: String, val url: String, val width: Int = 0, val height: Int = 0) {
        val pixels: Long get() = width.toLong() * height
    }

    private var offsetMs = 0L

    fun fetchStreams(): List<Stream> {
        // La mayoría de las cámaras rechazan el token si su reloj difiere del nuestro,
        // así que se calcula la diferencia con GetSystemDateAndTime (no requiere login).
        offsetMs = try {
            fetchTimeOffset()
        } catch (_: Exception) {
            0L
        }

        val candidates = mutableListOf<String>()
        try {
            fetchMediaUrl()?.let { candidates.add(it) }
        } catch (e: OnvifException) {
            if (e.auth) throw e
        } catch (_: Exception) {
        }
        if (deviceUrl !in candidates) candidates.add(deviceUrl)

        var lastError: Exception? = null
        for (url in candidates) {
            try {
                val streams = fetchStreamsFrom(url)
                if (streams.isNotEmpty()) return streams
            } catch (e: OnvifException) {
                if (e.auth) throw e
                lastError = e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw OnvifException(lastError?.message ?: "La cámara no devolvió ningún stream")
    }

    private fun fetchTimeOffset(): Long {
        val xml = soap(deviceUrl, "<tds:GetSystemDateAndTime/>", auth = false)
        val block = Regex("UTCDateTime>(.*?)</(?:\\w+:)?UTCDateTime", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return 0L

        fun num(tag: String): Int? =
            Regex("<(?:\\w+:)?$tag>(\\d+)<").find(block)?.groupValues?.get(1)?.toIntOrNull()

        val h = num("Hour") ?: return 0L
        val mi = num("Minute") ?: return 0L
        val s = num("Second") ?: return 0L
        val y = num("Year") ?: return 0L
        val mo = num("Month") ?: return 0L
        val d = num("Day") ?: return 0L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(y, mo - 1, d, h, mi, s)
        }
        return cal.timeInMillis - System.currentTimeMillis()
    }

    private fun fetchMediaUrl(): String? {
        val xml = soap(
            deviceUrl,
            "<tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities>",
            auth = true,
        )
        return Regex("<(?:\\w+:)?Media>.*?<(?:\\w+:)?XAddr>([^<]+)<", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1)?.trim()?.let { unescape(it) }
    }

    private fun fetchStreamsFrom(mediaUrl: String): List<Stream> {
        val xml = soap(mediaUrl, "<trt:GetProfiles/>", auth = true)
        val profileRe = Regex(
            "<(?:\\w+:)?Profiles\\s[^>]*token=[\"']([^\"']+)[\"'][^>]*>(.*?)</(?:\\w+:)?Profiles>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val nameRe = Regex("<(?:\\w+:)?Name>([^<]*)<")
        val resRe = Regex("<(?:\\w+:)?Width>(\\d+)</(?:\\w+:)?Width>\\s*<(?:\\w+:)?Height>(\\d+)<")
        val uriRe = Regex("<(?:\\w+:)?Uri>([^<]+)<")

        val result = mutableListOf<Stream>()
        for (m in profileRe.findAll(xml)) {
            val token = m.groupValues[1]
            val block = m.groupValues[2]
            val name = nameRe.find(block)?.groupValues?.get(1)?.let { unescape(it) }?.ifBlank { null } ?: token
            val resMatch = resRe.find(block)
            val w = resMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = resMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val label = if (w > 0 && h > 0) "$name (${w}x$h)" else name

            val body = "<trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream>" +
                "<tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup>" +
                "<trt:ProfileToken>${escape(token)}</trt:ProfileToken></trt:GetStreamUri>"
            try {
                val resp = soap(mediaUrl, body, auth = true)
                val uri = uriRe.find(resp)?.groupValues?.get(1)?.let { unescape(it).trim() } ?: continue
                result.add(Stream(label, withCredentials(uri), w, h))
            } catch (e: OnvifException) {
                if (e.auth) throw e
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun withCredentials(uri: String): String {
        if (user.isEmpty()) return uri
        val idx = uri.indexOf("://")
        if (idx < 0) return uri
        val rest = uri.substring(idx + 3)
        if (rest.substringBefore('/').contains('@')) return uri
        return uri.substring(0, idx + 3) + Uri.encode(user) + ":" + Uri.encode(pass) + "@" + rest
    }

    // ---------------------------------------------------------------- SOAP

    private fun soap(url: String, body: String, auth: Boolean): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.outputStream.use { it.write(envelope(body, auth).toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code == 401 || text.contains("NotAuthorized", ignoreCase = true)) {
                throw OnvifException("Usuario o contraseña incorrectos", auth = true)
            }
            if (code !in 200..299) throw OnvifException("La cámara respondió HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun envelope(body: String, auth: Boolean): String {
        val header = if (auth && user.isNotEmpty()) securityHeader() else ""
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" " +
            "xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\" " +
            "xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
            "<s:Header>$header</s:Header><s:Body>$body</s:Body></s:Envelope>"
    }

    private fun securityHeader(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val created = fmt.format(java.util.Date(System.currentTimeMillis() + offsetMs))
        val sha1 = MessageDigest.getInstance("SHA-1")
        sha1.update(nonce)
        sha1.update(created.toByteArray(Charsets.UTF_8))
        sha1.update(pass.toByteArray(Charsets.UTF_8))
        val digest = Base64.encodeToString(sha1.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)

        return "<wsse:Security xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" " +
            "xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
            "<wsse:UsernameToken><wsse:Username>${escape(user)}</wsse:Username>" +
            "<wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">$digest</wsse:Password>" +
            "<wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">$nonceB64</wsse:Nonce>" +
            "<wsu:Created>$created</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/** Resultado de elegir qué stream es el principal y cuál el secundario (puede no haberlo). */
data class StreamChoice(val main: OnvifClient.Stream, val sub: OnvifClient.Stream?)

/**
 * Principal = el de mayor resolución. Secundario = el de menor resolución, solo si es
 * realmente más pequeño que el principal y tiene otra URL. Si no se puede determinar
 * (un solo perfil, resoluciones iguales o desconocidas) no hay secundario y el mosaico
 * usará el principal.
 */
fun pickMainAndSub(streams: List<OnvifClient.Stream>): StreamChoice {
    require(streams.isNotEmpty()) { "No hay streams" }
    if (streams.size == 1) return StreamChoice(streams[0], null)

    val known = streams.filter { it.pixels > 0 }
    if (known.size < 2) return StreamChoice(known.firstOrNull() ?: streams[0], null)

    val main = known.maxByOrNull { it.pixels }!!
    val smallest = known.minByOrNull { it.pixels }!!
    val sub = smallest.takeIf { it.pixels < main.pixels && it.url != main.url }
    return StreamChoice(main, sub)
}
