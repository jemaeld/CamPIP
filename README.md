# TV Cam PiP

App para Android TV / Chromecast con Google TV que reproduce cámaras IP (RTSP, compatibles con ONVIF) y las deja **siempre encima en Picture-in-Picture** mientras usas otra app como YouTube.

## Cómo funciona

1. Abres la app y agregas tus cámaras (ver abajo).
2. Seleccionas una cámara. Con la casilla **"Enviar a PiP al seleccionar"** activada (por defecto), la cámara pasa directo a una ventana flotante y la app te deja en el launcher.
3. Abres YouTube (o lo que quieras): la ventana de la cámara sigue encima.
4. Si abres la cámara a pantalla completa, pulsa OK para mostrar los botones **Enviar a PiP** / **Volver**. En Android 12+ también entra sola a PiP al pulsar Inicio.

En la lista: **mantén OK** (o tecla **Menú**) sobre una cámara para ver en pantalla completa, ver en PiP, editar o eliminar.

## Formas de agregar cámaras

| Botón | Qué hace |
|---|---|
| **Buscar cámaras (ONVIF)** | Descubre cámaras en la red (WS-Discovery), pide usuario/contraseña y obtiene solo las URLs RTSP de cada perfil (principal, secundario...). Si no encuentra nada, ofrece consultar por IP o agregar manual. |
| **Agregar desde el celular** | Levanta un servidor web local y muestra un QR. Desde el celular (misma red) puedes agregar por URL RTSP, agregar por ONVIF (IP + usuario) y eliminar cámaras. El servidor solo vive mientras el diálogo está abierto y exige un token aleatorio incluido en el QR. |
| **Agregar manual** | Escribes nombre y URL RTSP con el control. |

Si el descubrimiento o ONVIF fallan (ONVIF desactivado, otra subred, autenticación Digest HTTP), siempre queda la URL RTSP manual.

## URLs RTSP de ejemplo

| Marca | URL |
|---|---|
| Genérica | `rtsp://usuario:clave@192.168.1.50:554/stream1` |
| Hikvision | `rtsp://usuario:clave@IP:554/Streaming/Channels/101` |
| Dahua | `rtsp://usuario:clave@IP:554/cam/realmonitor?channel=1&subtype=0` |
| Reolink | `rtsp://usuario:clave@IP:554/h264Preview_01_main` |
| Tapo / TP-Link | `rtsp://usuario:clave@IP:554/stream1` |

## Compilar

No necesitas Android Studio: el workflow de GitHub Actions compila el APK en cada push.

1. Ve a la pestaña **Actions** del repo, abre la última ejecución y descarga el artefacto `tvcampip-debug-apk`.
2. Si creas un tag (`git tag v0.2.0 && git push --tags`) el APK se publica también en **Releases**.

Localmente: `gradle assembleDebug` (JDK 17 y Android SDK).

## Instalar en el TV / Chromecast

Activa las opciones de desarrollador y la depuración USB/de red, y luego:

```bash
adb connect IP_DEL_TV:5555
adb install -r tvcampip-debug.apk
```

## Permiso de PiP

Si no entra a PiP: **Ajustes > Apps > Acceso especial > Imagen en imagen** y activa **TV Cam PiP**.

## Detalles técnicos

- Kotlin, minSdk 26, Media3 ExoPlayer con `RtspMediaSource` (RTP sobre TCP, buffers pequeños para baja latencia).
- Reintento automático cada 3 s si se pierde el stream.
- ONVIF: WS-Discovery por multicast UDP, y SOAP con WS-Security UsernameToken (PasswordDigest), compensando la diferencia de reloj con la cámara.
- Servidor web local sin dependencias (ServerSocket) y QR con `zxing-core`.
- Las cámaras se guardan localmente en el dispositivo.

## Pendientes / ideas

- Autenticación HTTP Digest para cámaras ONVIF que la exijan además de WS-Security.
- Varias cámaras en mosaico, audio activable, tamaño y esquina del PiP.
