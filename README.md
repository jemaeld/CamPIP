# TV Cam PiP

App para Android TV / Chromecast con Google TV que reproduce cámaras IP (RTSP, compatibles con ONVIF) y las deja **siempre encima en Picture-in-Picture** mientras usas otra app como YouTube.

## Cómo funciona

1. Abres la app y agregas tus cámaras con su URL RTSP.
2. Seleccionas una cámara. Con la casilla **"Enviar a PiP al seleccionar"** activada (por defecto), la cámara pasa directo a una ventana flotante y la app te deja en el launcher.
3. Abres YouTube (o lo que quieras): la ventana de la cámara sigue encima.
4. Si abres la cámara a pantalla completa, pulsa OK para mostrar los botones **Enviar a PiP** / **Volver**. En Android 12+ también entra sola a PiP al pulsar Inicio.

En la lista: **mantén OK** (o tecla **Menú**) sobre una cámara para ver en pantalla completa, ver en PiP, editar o eliminar.

## URLs RTSP de ejemplo

| Marca | URL |
|---|---|
| Genérica | `rtsp://usuario:clave@192.168.1.50:554/stream1` |
| Hikvision | `rtsp://usuario:clave@IP:554/Streaming/Channels/101` |
| Dahua | `rtsp://usuario:clave@IP:554/cam/realmonitor?channel=1&subtype=0` |
| Reolink | `rtsp://usuario:clave@IP:554/h264Preview_01_main` |
| Tapo / TP-Link | `rtsp://usuario:clave@IP:554/stream1` |

Para cámaras ONVIF, la URL RTSP aparece en la configuración de la cámara o en ONVIF Device Manager.

## Compilar

No necesitas Android Studio: el workflow de GitHub Actions compila el APK en cada push.

1. Ve a la pestaña **Actions** del repo, abre la última ejecución y descarga el artefacto `tvcampip-debug-apk`.
2. Si creas un tag (`git tag v0.1.0 && git push --tags`) el APK se publica también en **Releases**.

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
- Las cámaras se guardan localmente en el dispositivo.

## Pendientes / ideas

- Descubrimiento automático ONVIF (WS-Discovery) y obtención de la URL RTSP.
- Agregar cámaras desde el teléfono (servidor web local) para no teclear con el control.
- Varias cámaras en mosaico, audio activable, tamaño y esquina del PiP.
