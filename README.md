# TV Cam PiP

App para Android TV / Chromecast con Google TV que muestra **todas tus cámaras IP (RTSP, compatibles con ONVIF) en un mosaico en vivo** y deja la que elijas **siempre encima en Picture-in-Picture** mientras usas otra app como YouTube.

## Cómo funciona

1. Al abrir la app ves el **mosaico** con todas las cámaras activas, a pantalla completa. Para abrir el **menú**, ve con las flechas hasta la última cámara del borde derecho y pulsa **derecha** una vez más (o la tecla **Menú**): se abre un panel pequeño y translúcido a la derecha con dos iconos: ⚙ *Gestionar cámaras* y ⏻ *Salir*. Izquierda o Atrás lo cierran. Enviar la cámara a PiP (o a la ventana flotante) se hace con la flecha **Atrás** o **Inicio**. La cuadrícula se adapta sola al número de cámaras (1, 2x1, 2x2, 3x2...).
2. Muévete con las flechas. **OK** sobre una cámara la marca con ★ PiP: esa es la que quedará flotando al salir. **Mantén OK** para verla a pantalla completa.
3. Al pulsar **Inicio** (o *Enviar a PiP* en el menú) la app pasa a PiP mostrando solo la cámara elegida; las demás se detienen para ahorrar ancho de banda. En Android 12+ entra sola a PiP al pulsar Inicio.
4. La **flecha atrás** en el mosaico hace lo mismo que el botón: deja la cámara elegida en PiP (o en ventana flotante) y te lleva al inicio, sin pulsar nada más.
5. **Gestionar cámaras** (botón arriba o tecla Menú): agrega, edita y elimina cámaras (mantén OK sobre una cámara o pulsa Menú para ver *Editar* y *Eliminar*), y usa la **casilla** de cada una para decidir si aparece en el mosaico.

## Formas de agregar cámaras (en Gestionar cámaras)

| Botón | Qué hace |
|---|---|
| **Buscar cámaras** (ONVIF) | Descubre cámaras en la red (WS-Discovery), pide usuario/contraseña y obtiene solas las URLs RTSP de cada perfil (principal, secundario...). Si no encuentra nada, ofrece consultar por IP o agregar manual. |
| **Desde el celular** | Levanta un servidor web local y muestra un QR. Desde el celular (misma red) puedes agregar por URL RTSP, agregar por ONVIF (IP + usuario), mostrar/ocultar, **editar** y eliminar cámaras. El servidor solo vive mientras el diálogo está abierto y exige un token aleatorio incluido en el QR. |
| **Agregar manual** | Escribes nombre y URL RTSP con el control. |
| **Importar de archivo** | Carga muchas cámaras de golpe desde un archivo `.json` (ver abajo). |

## Exportar e importar cámaras

Para no agregar cada cámara a mano en cada TV (o para respaldar la lista):

- **En la app:** *Gestionar cámaras → Exportar a archivo* guarda un `.json` con todas las cámaras (usa el selector de archivos del TV). *Importar de archivo* lo lee y pregunta si **Agregar** a las existentes (omite las que ya tienen la misma URL principal) o **Reemplazar** todas.
- **En el servidor web** (*Desde el celular*): sección **Exportar e importar** con un enlace para descargar el archivo y un formulario para subir uno (o pegar su contenido), con opción de reemplazar.
- Si el TV no tiene selector de archivos, la app usa su carpeta `/sdcard/Android/data/com.tvcampip/files/tvcampip-camaras.json` y te indica el comando `adb pull` / `adb push`.

Formato (también se acepta una lista simple `[ {...}, {...} ]`):

```json
{
  "app": "TvCamPip",
  "version": 1,
  "cameras": [
    { "name": "Entrada", "url": "rtsp://usuario:clave@192.168.1.50:554/stream1",
      "gridUrl": "rtsp://usuario:clave@192.168.1.50:554/stream2", "enabled": true }
  ]
}
```

Solo `name` y `url` son obligatorios (`gridUrl` y `enabled` son opcionales). Las entradas inválidas se omiten y se informa cuántas.
**Ojo:** el archivo contiene usuario y contraseña de las cámaras en texto plano; guárdalo en un lugar seguro y no lo subas al repositorio.

## Stream principal y secundario

Cada cámara tiene una **URL principal** (alta resolución) y una **URL secundaria** opcional (baja resolución):

- El **mosaico** usa la secundaria. Si la cámara no tiene secundaria, usa la principal.
- **Pantalla completa** y **PiP** usan siempre la principal.
- Con **ONVIF** la app detecta sola cuál es cuál: principal = perfil de mayor resolución, secundario = el de menor resolución. Si hay un solo perfil, las resoluciones son iguales o no se pueden leer, no se asigna secundaria y el mosaico usa el principal. Antes de agregar, te muestra qué eligió.
- Al agregar o editar manualmente (en el TV o desde el celular) hay un campo opcional "URL secundaria para el mosaico".

## Rendimiento con varias cámaras

Reproducir 6 o más streams a la vez exige mucho al decodificador del TV. Usar el stream secundario lo reduce bastante. Si el hardware se queda sin decodificadores, la app recurre a decodificación por software (más lenta); si ves cortes, baja la resolución del secundario o reduce las cámaras visibles con las casillas.

## URLs RTSP de ejemplo

| Marca | URL |
|---|---|
| Genérica | `rtsp://usuario:clave@192.168.1.50:554/stream1` |
| Hikvision | `rtsp://usuario:clave@IP:554/Streaming/Channels/101` (principal) / `102` (secundario) |
| Dahua | `rtsp://usuario:clave@IP:554/cam/realmonitor?channel=1&subtype=0` (principal) / `subtype=1` (secundario) |
| Reolink | `rtsp://usuario:clave@IP:554/h264Preview_01_main` / `_sub` |
| Tapo / TP-Link | `rtsp://usuario:clave@IP:554/stream1` / `stream2` |

## Compilar

No necesitas Android Studio: el workflow de GitHub Actions compila el APK en cada push a `main`.
Localmente: `gradle assembleDebug` (JDK 17 y Android SDK).

## Actualizaciones automáticas

Cada push a `main` publica un **Release** con el APK firmado (`tvcampip.apk`, etiqueta `build-N`). Las TV con la app instalada lo detectan al abrirla, muestran **"Hay una actualización"** y, si aceptas, descargan e instalan la nueva versión. También puedes forzar la búsqueda en *Gestionar cámaras → Actualizar app*.

Para que funcione, Android exige que **todas las versiones estén firmadas con la misma llave**. Configuración (una sola vez):

1. En una PC con JDK instalado: `./scripts/generate-keystore.sh`. Imprime 4 valores.
2. En GitHub: repo → **Settings → Secrets and variables → Actions → New repository secret**, y crea `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` y `KEY_PASSWORD` con esos valores.
3. **Guarda el archivo `.jks` y la contraseña** en un lugar seguro. Si se pierden, no se podrá actualizar la app instalada.
4. Haz un push (o ejecuta el workflow a mano en Actions → *Run workflow*). Si faltan los Secrets, el workflow solo compila un APK debug de respaldo y no publica Release.
5. Instala el `tvcampip.apk` del primer Release en cada TV. Si ya había una versión debug instalada, **desinstálala primero** (la firma es distinta) y se perderán las cámaras guardadas.
6. En cada TV, la primera vez que pulses *Actualizar ahora* el sistema pedirá permitir **"Instalar apps desconocidas"** para TV Cam PiP. Actívalo y vuelve a pulsar el botón.

Cada actualización pide confirmación del usuario (Android no permite instalar en silencio sin Play Store). Tras instalar, la app se cierra y hay que abrirla de nuevo.

## Instalar en el TV / Chromecast

Activa las opciones de desarrollador y la depuración USB/de red, y luego:

```bash
adb connect IP_DEL_TV:5555
adb -s IP_DEL_TV:5555 install -r tvcampip.apk
```

## Equipos sin PiP (ventana flotante)

Algunos equipos (varios ONN con Android TV) no traen la función de Picture-in-Picture. Para ellos, *Gestionar cámaras* tiene la casilla **Ventana flotante si el equipo no soporta PiP** (activada por defecto solo en esos equipos):

- El **tamaño** de la ventana se elige junto a la casilla (Pequeña 20 %, Mediana 28 %, Grande 40 % del ancho de pantalla).
- La **posición** se elige en el mismo grupo: una de las 4 esquinas o **Mover con el control remoto…**, que abre una pantalla con un recuadro que desplazas con las flechas (OK guarda, Atrás cancela).
- Al salir con Inicio, con la flecha atrás o con **Enviar a PiP**, la app abre una **ventana flotante** abajo a la derecha con la cámara elegida, encima de otras apps. Se cierra al volver a la app o con **Cerrar** en la notificación.
- En esos equipos **siempre se usa el stream secundario (baja resolución)** si la cámara lo tiene, también en pantalla completa.
- Necesita el permiso **Mostrar sobre otras apps**. Si el TV no tiene esa pantalla: `adb shell appops set com.tvcampip SYSTEM_ALERT_WINDOW allow`
- Para saber si un equipo soporta PiP: `adb shell pm list features | grep -i picture`. Si no sale `android.software.picture_in_picture`, es una limitación del firmware, no de la app.
- La ventana flotante no recibe control remoto (no toma el foco); es solo para ver. Para moverla o cambiar su tamaño se usa *Gestionar cámaras*.
- **Cerrarla:** abre la app (se cierra sola) y usa ☰ → **Salir**, que cierra la app y la ventana sin dejar nada encima. Inicio y la flecha atrás la vuelven a abrir a propósito.

### Si la ventana no se cierra o no aparece

Ejecuta estos comandos desde una PC con adb (cambia la IP por la de tu TV):

```bash
adb connect IP_DEL_TV:5555
# Dar el permiso "Mostrar sobre otras apps" (si el TV no tiene esa pantalla o no la guarda)
adb -s IP_DEL_TV:5555 shell appops set com.tvcampip SYSTEM_ALERT_WINDOW allow
# Permiso de notificaciones (Android 13+), para ver el aviso con el botón Cerrar
adb -s IP_DEL_TV:5555 shell pm grant com.tvcampip android.permission.POST_NOTIFICATIONS
# Forzar el cierre de la app (y de la ventana flotante) si se quedó pegada
adb -s IP_DEL_TV:5555 shell am force-stop com.tvcampip
# Ver el estado del permiso (debe decir "allow")
adb -s IP_DEL_TV:5555 shell appops get com.tvcampip SYSTEM_ALERT_WINDOW
# Registro de errores de la app
adb -s IP_DEL_TV:5555 logcat -d | grep -i "tvcampip\|OverlayService" | tail -40
```

`am force-stop` es la salida de emergencia: mata la app y su ventana flotante al instante.

## Permiso de PiP

Si no entra a PiP: **Ajustes > Apps > Acceso especial > Imagen en imagen** y activa **TV Cam PiP**.

## Detalles técnicos

- Kotlin, minSdk 26, Media3 ExoPlayer con `RtspMediaSource` (RTP sobre TCP, buffers pequeños para baja latencia, audio silenciado en el mosaico).
- `MosaicLayout` elige el número de columnas que maximiza el tamaño de cada mosaico para la pantalla.
- Reintento automático cada 3 s por cámara si se pierde el stream.
- ONVIF: WS-Discovery por multicast UDP, y SOAP con WS-Security UsernameToken (PasswordDigest), compensando la diferencia de reloj con la cámara.
- Servidor web local sin dependencias (ServerSocket) y QR con `zxing-core`.
- Las cámaras se guardan localmente en el dispositivo.

## Pendientes / ideas

- Autenticación HTTP Digest para cámaras ONVIF que la exijan además de WS-Security.
- Orden manual de los mosaicos, audio activable, tamaño y esquina del PiP.
