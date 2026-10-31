package com.tvcampip

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Long,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val size: Long,
)

/** Consulta los Releases de GitHub, descarga el APK y lo instala con PackageInstaller. */
object Updater {

    private const val REPO = "jemaeld/CamPIP"
    private const val ASSET_NAME = "tvcampip.apk"
    private const val USER_AGENT = "TvCamPip-Updater"

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /**
     * Bloquea la red. Devuelve el último Release publicado, o null si todavía no hay ninguno
     * válido. El número de versión es el número final de la etiqueta (build-12 -> 12).
     */
    fun fetchLatest(): UpdateInfo? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("GitHub respondió HTTP $code")

            val json = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val tag = json.optString("tag_name")
            val versionCode = Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == ASSET_NAME) {
                    return UpdateInfo(
                        versionCode = versionCode,
                        tag = tag,
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Bloquea la red. Descarga el APK a la caché de la app informando el avance en %. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        val file = File(dir, ASSET_NAME)
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("La descarga respondió HTTP $code")

            val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else info.size
            conn.inputStream.use { input ->
                FileOutputStream(file).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPercent = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            return file
        } finally {
            conn.disconnect()
        }
    }

    /** Entrega el APK al instalador del sistema; el resultado llega a [InstallReceiver]. */
    fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Si el sistema lo permite, actualiza sin pedir confirmación de nuevo.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            file.inputStream().use { input ->
                session.openWrite("package", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java).setAction(InstallReceiver.ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Flujo con diálogos: busca una versión nueva y, si el usuario acepta, la descarga e instala. */
class UpdateFlow(private val activity: AppCompatActivity) {

    private fun alive() = !activity.isFinishing && !activity.isDestroyed

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    /** [manual] = true avisa también cuando ya se tiene la última versión o si falla la consulta. */
    fun check(manual: Boolean) {
        Thread {
            val result = try {
                Result.success(Updater.fetchLatest())
            } catch (e: Exception) {
                Result.failure<UpdateInfo?>(e)
            }
            activity.runOnUiThread {
                if (!alive()) return@runOnUiThread
                result.fold(
                    onSuccess = { info ->
                        val current = Updater.currentVersionCode(activity)
                        if (info != null && info.versionCode > current) {
                            promptUpdate(info, current)
                        } else if (manual) {
                            toast("Ya tienes la última versión (build $current)")
                        }
                    },
                    onFailure = { if (manual) toast("No se pudo buscar actualizaciones: ${it.message}") },
                )
            }
        }.start()
    }

    private fun promptUpdate(info: UpdateInfo, current: Long) {
        val notes = if (info.notes.isNotEmpty()) "\n\n${info.notes.take(300)}" else ""
        AlertDialog.Builder(activity)
            .setTitle("Hay una actualización")
            .setMessage("Versión nueva: build ${info.versionCode}\nInstalada: build $current$notes")
            .setPositiveButton("Actualizar ahora") { _, _ -> startUpdate(info) }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun startUpdate(info: UpdateInfo) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast("Activa «Permitir de esta fuente» para TV Cam PiP y vuelve a pulsar Actualizar")
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            } catch (_: Exception) {
                toast("Ve a Ajustes > Apps > Acceso especial > Instalar apps desconocidas y activa TV Cam PiP")
            }
            return
        }

        val progress = AlertDialog.Builder(activity)
            .setMessage("Descargando actualización… 0%")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            try {
                val file = Updater.download(activity, info) { percent ->
                    activity.runOnUiThread { progress.setMessage("Descargando actualización… $percent%") }
                }
                activity.runOnUiThread { progress.setMessage("Instalando…") }
                Updater.install(activity, file)
                activity.runOnUiThread { progress.dismiss() }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    progress.dismiss()
                    toast("No se pudo actualizar: ${e.message}")
                }
            }
        }.start()
    }
}
