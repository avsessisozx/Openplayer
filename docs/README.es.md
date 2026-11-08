<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Reproductor de vídeo y música para Android, gratuito, de código abierto y sin anuncios.<br>Gestos en pantalla, decodificadores HW / HW+ / SW, búsqueda y traducción de subtítulos, vídeo 360°, reproducción por red SMB / FTP / WebDAV.</b>
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> ·
  <a href="README.tr.md">Türkçe</a> ·
  <b>Español</b> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.it.md">Italiano</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.pt-BR.md">Português</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## Qué es

OpenPlayer es un reproductor de vídeo de código abierto para Android, sin anuncios ni seguimiento. Ofrece como software libre las funciones útiles de los reproductores populares de código cerrado: gestos en pantalla, gestión de subtítulos, elección de decodificador, fuentes de red y más. Todo está escrito desde cero.

La aplicación está disponible en 12 idiomas. Las traducciones son automáticas y no las han revisado hablantes nativos; se agradecen las correcciones.

## Descarga

Descarga `OpenPlayer.apk` desde la [página de versiones](https://github.com/avsessisozx/Openplayer/releases) e instálalo (Android 7.0+). Puede que debas permitir la instalación desde fuentes desconocidas.

> **Descarga solo desde la página de versiones.** La clave de firma es pública (código abierto); un APK de otro sitio podría ser una copia manipulada que se instala sobre la app real.

> **Estado: beta.** Compilado y probado en CI y en un emulador, pero aún sin pruebas amplias en dispositivos reales. Informa de los problemas en [Issues](https://github.com/avsessisozx/Openplayer/issues).

## Funciones

- **Motor de reproducción:** libmpv (mpv + FFmpeg). Decodificadores: **HW** (solo hardware, MediaCodec), **HW+** (hardware; si falla, hardware con copia y luego software), **SW** (solo software, FFmpeg); se pueden cambiar durante la reproducción.
- **Nuevo (beta23):** subtítulos ASS/SSA con estilo completo (libass); traducción de subtítulos incrustados (MKV/WebM/MP4); modo nocturno de audio.
- **Reproducción:** velocidad 0,25x–4x, 2x con pulsación larga, repetición A-B, temporizador de apagado, ecualizador, aumento de volumen hasta 200 %, retardo de audio, reanudar donde lo dejaste, PiP, reproducción en segundo plano, bloqueo de pantalla y control parental, capturas de pantalla.
- **360° / VR:** vídeo y fotos 360°, sensor de movimiento, modo gafas VR.
- **Gestos:** franjas izquierda/centro/derecha (brillo, volumen, velocidad, avance, subtítulos, zoom), deslizamiento horizontal para avanzar, doble toque, zoom con pellizco.
- **Subtítulos:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD y más; ajuste de retardo/velocidad, detección automática de codificación, estilo y fuente propia; búsqueda en OpenSubtitles.com (requiere tu propia [clave API](https://www.opensubtitles.com/consumers)); **traducción de subtítulos** (MyMemory, LibreTranslate, IA compatible con OpenAI).
- **Archivos:** solo carpetas con vídeos, cuadrícula/lista, ordenación, carpetas ocultas y privada (bloqueada), marcadores, gestor multimedia.
- **Red:** SMB, FTP, WebDAV; direcciones HTTP/HTTPS/RTSP/HLS/DASH.
- **Reproductor de música** (opcional): vistas por canciones, álbumes, artistas y carpetas, cola, ecualizador, edición de etiquetas.

## Limitaciones conocidas

Los subtítulos de imagen (VobSub, PGS) los muestra mpv, pero no se pueden traducir y no aparecen en modo 360°; sin Chromecast ni desentrelazado; los subtítulos externos de vídeos en red no se encuentran automáticamente.

## Compilación

Cada cambio se compila con **GitHub Actions**. En local: JDK 17, Android SDK y `gradle assembleDebug`.

## Privacidad y seguridad

Sin anuncios, seguimiento ni analítica. Las contraseñas de servidores de red y de OpenSubtitles se guardan **sin cifrar** en el almacenamiento privado de la app. `app/openplayer-test.jks` es una clave de firma de prueba pública. Más información: [SECURITY.md](../SECURITY.md).

## Licencia

GNU AGPLv3 ([LICENSE](../LICENSE)). Motor de reproducción: [mpv](https://mpv.io) (GPL-2.0+), [FFmpeg](https://ffmpeg.org) (GPL-3.0+), [libass](https://github.com/libass/libass) (ISC), [libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1+), [dav1d](https://code.videolan.org/videolan/dav1d) (BSD-2-Clause), FreeType, HarfBuzz, FriBidi, mbedTLS, libxml2, Lua — a través de [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android) (MIT); [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0). Lista completa, licencias y archivos de código fuente: [mpv-licenses.md](mpv-licenses.md).
