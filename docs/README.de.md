<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Kostenloser, quelloffener und werbefreier Video- und Musikplayer für Android.<br>Gestensteuerung, HW- / HW+- / SW-Decoder, Untertitelsuche und -übersetzung, 360°-Video, Netzwerkwiedergabe per SMB / FTP / WebDAV.</b>
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> ·
  <a href="README.tr.md">Türkçe</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.it.md">Italiano</a> ·
  <b>Deutsch</b> ·
  <a href="README.pt-BR.md">Português</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## Was ist das?

OpenPlayer ist ein quelloffener Android-Videoplayer ohne Werbung und ohne Tracking. Er bietet die nützlichen Funktionen bekannter proprietärer Player als freie Software: Gesten auf dem Bildschirm, Untertitelverwaltung, Decoderwahl, Netzwerkquellen und mehr. Alles ist von Grund auf neu geschrieben.

Die App gibt es in 12 Sprachen. Die Übersetzungen sind maschinell erstellt und nicht von Muttersprachlern geprüft; Korrekturen sind willkommen.

## Download

Lade `OpenPlayer.apk` von der [Release-Seite](https://github.com/avsessisozx/Openplayer/releases) herunter und installiere sie (Android 7.0+). Eventuell musst du die Installation aus unbekannten Quellen erlauben.

> **Status: Beta.** Auf CI und Emulator gebaut und getestet, aber noch nicht umfassend auf echten Geräten. Probleme bitte unter [Issues](https://github.com/avsessisozx/Openplayer/issues) melden.

## Funktionen

- **Decoder:** HW, HW+ (FFmpeg-Erweiterung: AC3, E-AC3, DTS, TrueHD, FLAC, Opus …) und SW, während der Wiedergabe umschaltbar. libVLC als Ersatz-Engine für Formate, die ExoPlayer nicht öffnet.
- **Wiedergabe:** Tempo 0,25x–4x, 2x per langem Druck, A-B-Wiederholung, Schlaf-Timer, Equalizer, Lautstärke bis 200 %, Audioverzögerung, Fortsetzen an der letzten Stelle, PiP, Hintergrundwiedergabe, Bildschirm- und Kindersperre, Screenshots.
- **360° / VR:** 360°-Videos und -Fotos, Bewegungssensor, VR-Brillenmodus.
- **Gesten:** linke/mittlere/rechte Zone (Helligkeit, Lautstärke, Tempo, Vor-/Zurückspulen, Untertitel, Zoom), horizontales Wischen zum Spulen, Doppeltippen, Zoomen per Pinch.
- **Untertitel:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD u. a.; Verzögerung/Tempo einstellbar, automatische Zeichenkodierung, Stil und eigene Schrift; Suche bei OpenSubtitles.com (eigener [API-Schlüssel](https://www.opensubtitles.com/consumers) nötig); **Untertitelübersetzung** (MyMemory, LibreTranslate, OpenAI-kompatible KI).
- **Dateien:** nur Ordner mit Videos, Raster/Liste, Sortierung, versteckte und private (gesperrte) Ordner, Lesezeichen, Medienverwaltung.
- **Netzwerk:** SMB, FTP, WebDAV; HTTP/HTTPS/RTSP/HLS/DASH-Adressen.
- **Musikplayer** (optional): Ansichten nach Titeln, Alben, Interpreten und Ordnern, Warteschlange, Equalizer, Tag-Bearbeitung.

## Bekannte Einschränkungen

Kein vollständiges ASS/SSA (libass); bildbasierte Untertitel (VobSub, PGS) werden nicht angezeigt; kein Chromecast, kein Deinterlacing; externe Untertitel für Netzwerkvideos werden nicht automatisch gefunden.

## Bauen

Jede Änderung wird von **GitHub Actions** gebaut. Lokal: JDK 17, Android SDK und `gradle assembleDebug`.

## Datenschutz und Sicherheit

Keine Werbung, kein Tracking, keine Analyse. Passwörter für Netzwerkserver und OpenSubtitles werden **unverschlüsselt** im privaten App-Speicher abgelegt. `app/openplayer-test.jks` ist ein öffentlicher Test-Signaturschlüssel. Details: [SECURITY.md](../SECURITY.md).

## Lizenz

GNU AGPLv3 ([LICENSE](../LICENSE)). Verwendete Komponenten: [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
