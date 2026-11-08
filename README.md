<p align="center">
  <img src="docs/icon.png" width="120" alt="OpenPlayer icon">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Free, open-source, ad-free video and music player for Android.</b><br>
  Gesture controls, mpv engine with HW / HW+ / SW decoding, full ASS/SSA subtitles, subtitle search and translation, 360° video, SMB / FTP / WebDAV network playback.
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases/latest"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3 license"></a>
  <img src="https://img.shields.io/github/downloads/avsessisozx/Openplayer/total?color=555" alt="Downloads">
</p>

<p align="center">
  <b>English</b> ·
  <a href="docs/README.tr.md">Türkçe</a> ·
  <a href="docs/README.es.md">Español</a> ·
  <a href="docs/README.fr.md">Français</a> ·
  <a href="docs/README.it.md">Italiano</a> ·
  <a href="docs/README.de.md">Deutsch</a> ·
  <a href="docs/README.pt-BR.md">Português</a> ·
  <a href="docs/README.zh-CN.md">简体中文</a> ·
  <a href="docs/README.ja.md">日本語</a> ·
  <a href="docs/README.ko.md">한국어</a> ·
  <a href="docs/README.ru.md">Русский</a> ·
  <a href="docs/README.ar.md">العربية</a>
</p>

---

## What it is

OpenPlayer is an open-source Android video player with no ads and no tracking. It aims to offer, as free software, the useful features of popular closed-source players: screen gestures, subtitle management, decoder choice, network sources and more. Everything is written from scratch; no code, name, logo or design asset of any closed-source player is used.

The app is available in 12 languages (English, Türkçe, Español, Français, Italiano, Deutsch, Português, 简体中文, 日本語, 한국어, Русский, العربية). Translations are machine-made and have not been reviewed by native speakers; corrections are welcome.

## Download

Get `OpenPlayer.apk` from the [Releases page](../../releases) and install it (Android 7.0+). You may need to allow installing from unknown sources.

> **Only download from the Releases page.** The signing key is public (open source), so an APK from anywhere else could be a tampered copy that installs over the real app.

> **Status: beta.** Built and tested on CI and an emulator, but not yet extensively tested on real devices. Report problems in [Issues](../../issues).

## Features

**Playback**
- Engine: **libmpv** (mpv + FFmpeg). Decoder modes: **HW** (hardware only, MediaCodec), **HW+** (hardware; if that fails, hardware with copy-back, then software), **SW** (software only, FFmpeg). Switchable during playback; if a file fails to play, the next mode is tried automatically (optional).
- **New in beta23:** full-style ASS/SSA subtitles (libass), translation of embedded subtitle tracks (MKV/WebM/MP4), audio night mode (compressor)
- Speed (0.25x–4x, custom), **long-press for 2x**, A-B repeat, repeat / shuffle, sleep timer, night mode
- Equalizer, volume boost up to 200%, bass / stereo widening, reverse stereo / mono, reverb, audio delay, add an external audio file
- Resume where you left off, clock / battery / time in the corner, ±N s seek buttons
- Mirror and vertical flip, aspect ratio (original, 1:1 … 2.39:1), pinch-zoom and pan, rotation modes, screenshots
- Background playback (notification and media keys), Picture-in-Picture, screen lock and child lock
- 360° / VR video and photos, with motion sensor, VR headset mode and several lens / 3D formats
- Keyboard, mouse wheel and Bluetooth / wired media keys

**Screen gestures**
- The screen is split into left / middle / right strips with adjustable borders. Each strip's vertical swipe can control brightness, volume, speed, seeking, subtitle position / size or zoom
- Horizontal swipe to seek; double tap to play / pause or skip forward / back

**Subtitles**
- Formats: SRT, WebVTT, SSA/ASS (full styling via libass), SAMI, MicroDVD, MPL2, TMPlayer; embedded subtitle tracks
- Manual delay / speed adjustment, automatic character encoding detection, size / color / background / edge / position / alignment, your own font (.ttf/.otf)
- Subtitle gestures: horizontal swipe for previous / next line, vertical swipe for position, pinch for size
- Online subtitle search (OpenSubtitles.com). You need to enter [your own API key](https://www.opensubtitles.com/consumers) under Settings → Subtitles
- **Subtitle translation:** translate a loaded subtitle file into another language and save it as a new .srt. Providers: MyMemory (no account), LibreTranslate-compatible address, OpenAI-compatible AI address (Groq, OpenRouter, Gemini, Ollama… with your own key)

**Files**
- Lists only folders that contain videos; thumbnails, duration, "new" and "watched" markers, sorting, grid / list views
- Rename, delete, share, info, hidden folders, private (locked) folder, bookmarks
- **Media manager:** storage summary; recently played, recently added, never opened and large-file lists
- **Network sources:** browse and play from SMB, FTP and WebDAV servers; HTTP / HTTPS / RTSP / HLS / DASH addresses
- **Music player** (optional): library by songs, albums, artists and folders, now-playing screen, queue, equalizer, tag editing

## Known limitations

- Image-based subtitles (VobSub, PGS) are drawn by mpv but cannot be translated and are not shown in 360° mode. Teletext is not drawn.
- Embedded subtitles can only be translated from MKV, WebM and MP4 files.
- Sidecar subtitles are not found automatically for network videos; add them manually from the player.
- No Chromecast, no deinterlacing, no decoder plugins.
- SMB share lists cannot be enumerated; type the share name.

## Building

No Android Studio needed: every push is built by **GitHub Actions** and the APK can be downloaded from the Actions tab. Locally: JDK 17, Android SDK and `gradle assembleDebug` (Gradle 8.14+).

## Privacy and security

- No ads, tracking or analytics. The app only connects to addresses you open, subtitle search and the servers you configure.
- Network-server and OpenSubtitles passwords are stored **unencrypted** in the app's private storage.
- `app/openplayer-test.jks` is a public **test signing key**, used so that releases can update over each other. Use your own key for store releases.
- See [SECURITY.md](SECURITY.md) for details and how to report a vulnerability.

## License

GNU AGPLv3. Anyone may use and modify it; modified versions (even offered as a network service) must be distributed under the same license with source code. See `LICENSE`.

OpenPlayer plays media with [mpv](https://mpv.io) (GPL-2.0+), [FFmpeg](https://ffmpeg.org) (GPL-3.0+), [libass](https://github.com/libass/libass) (ISC), [libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1+), [dav1d](https://code.videolan.org/videolan/dav1d) (BSD-2-Clause), FreeType, HarfBuzz, FriBidi, mbedTLS, libxml2, Lua — bundled through [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android) (MIT); [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0). Full component list, licenses and the source archive attached to every release: [docs/mpv-licenses.md](docs/mpv-licenses.md).
