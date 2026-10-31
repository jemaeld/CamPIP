# TV Cam PiP

App para Android TV / Chromecast con Google TV que muestra **todas tus cámaras IP (RTSP, compatibles con ONVIF) en un mosaico en vivo** y deja la que elijas **siempre encima en Picture-in-Picture** mientras usas otra app como YouTube.

## Cómo funciona

1. Al abrir la app ves el **mosaico** con todas las cámaras activas. La cuadrícula se adapta sola al número de cámaras (1, 2x1, 2x2, 3x2...).
2. Muévete con las flechas. **OK** sobre una cámara la marca con ★ PiP: esa es la que quedará flotando al salir. **Mantén OK** para verla a pantalla completa.
3. Al pulsar **Inicio** (o el botón **Enviar a PiP**) la app pasa a PiP mostrando solo la cámara elegida; las demás se detienen para ahorrar ancho de banda. En Android 12+ entra sola a PiP al pulsar Inicio.
4. **Gestionar cámaras** (botón arriba o tecla Menú): agrega, edita y elimina cámaras, y usa la **casilla** de cada una para decidir si aparece en el mosaico.

## Formas de agregar cámaras (en Gestionar cámaras)

| Botón | Qué hace |
|---|---|
| **Buscar cámaras (ONVIF)** | Descubre cámaras en la red (WS-Discovery), pide usuario/contraseña y obtiene solas las URLs RTSP de cada perfil (principal, secundario...). Si no encuentra nada, ofrece consultar por IP o agregar manual. |
| **Agregar desde el celular** | Levanta un servidor web local y muestra un QR. Desde el celular (misma red) puedes agregar por URL RTSP, agregar por ONVIF (IP + usuario), mostrar/ocultar y eliminar cámaras. El servidor solo vive mientras el diálogo está abierto y exige un token aleatorio incluido en el QR. |
| **Agregar manual** | Escribes nombre y URL RTSP con el control. |

## Rendimiento con varias cámaras

Reproducir 6 o más streams a la vez exige mucho al decodificador del TV. Recomendaciones:

- Usa el **stream secundario** (baja resolución) de las cámaras: el descubrimiento ONVIF lista todos los perfiles y puedes agregar el secundario.
- Si el hardware se queda sin decodificadores, la app recurre a decodificación por software (más lenta); si ves cortes, reduce resolución o cantidad de cámaras visibles con las casillas.

## URLs RTSP de ejemplo

| Marca | URL |
|---|---|
| Genérica | `rtsp://usuario:clave@192.168.1.50:554/stream1` |
| Hikvision | `rtsp://usuario:clave@IP:554/Streaming/Channels/101` (principal) / `102` (secundario) |
| Dahua | `rtsp://usuario:clave@IP:554/cam/realmonitor?channel=1&subtype=0` (principal) / `subtype=1` (secundario) |
| Reolink | `rtsp://usuario:clave@IP:554/h264Preview_01_main` / `_sub` |
| Tapo / TP-Link | `rtsp://usuario:clave@IP:554/stream1` / `stream2` |

## Compilar

No necesitas Android Studio: el workflow de GitHub Actions compila el APK en cada push.

1. Ve a la pestaña **Actions** del repo, abre la última ejecución y descarga el artefacto `tvcampip-debug-apk`.
2. Si creas un tag (`git tag v0.3.0 && git push --tags`) el APK se publica también en **Releases**.

Localmente: `gradle assembleDebug` (JDK 17 y Android SDK).

## Instalar en el TV / Chromecast

Activa las opciones de desarrollador y la depuración USB/de red, y luego:

```bash
adb connect IP_DEL_TV:5555
adb -s IP_DEL_TV:5555 install -r tvcampip-debug.apk
```

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

- URL secundaria por cámara para el mosaico (sub-stream) y la principal para pantalla completa y PiP.
- Autenticación HTTP Digest para cámaras ONVIF que la exijan además de WS-Security.
- Orden manual de los mosaicos, audio activable, tamaño y esquina del PiP.
