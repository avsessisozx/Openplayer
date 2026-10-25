<p align="center">
  <img src="docs/icon.png" width="120" alt="OpenPlayer icon">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Free, open-source, ad-free video and music player for Android.</b><br>
  Gesture controls, HW / HW+ / SW decoders, subtitle search and translation, 360° video, SMB / FTP / WebDAV network playback.
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

> **Status: beta.** Built and tested on CI and an emulator, but not yet extensively tested on real devices. Report problems in [Issues](../../issues).

## Features

**Playback**
- Three decoder modes: **HW** (MediaCodec), **HW+** (MediaCodec + FFmpeg extension: AC3, E-AC3, DTS, TrueHD, FLAC, Opus…), **SW** (software decoding, FFmpeg first). Switchable during playback. A libVLC engine is the fallback for formats ExoPlayer cannot open.
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
- Formats: SRT, WebVTT, SSA/ASS (colors, bold / italic / underline, position), SAMI, MicroDVD, MPL2, TMPlayer; embedded subtitle tracks
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

- No full ASS/SSA rendering (libass): styles, colors and position work, but animations, advanced effects, drawing commands and embedded fonts do not.
- Image-based subtitles (VobSub, PGS) and Teletext are not drawn.
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

OpenPlayer uses [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg extension, GPLv3), [smbj](https://github.com/hierynomus/smbj) (Apache-2.0), [Apache Commons Net](https://commons.apache.org/proper/commons-net/) (Apache-2.0), [OkHttp](https://square.github.io/okhttp/) (Apache-2.0), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0) and, as the fallback engine, [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
