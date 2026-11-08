<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Lettore video e musicale gratuito, open source e senza pubblicità per Android.<br>Gesti sullo schermo, decoder HW / HW+ / SW, ricerca e traduzione dei sottotitoli, video 360°, riproduzione in rete SMB / FTP / WebDAV.</b>
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
  <b>Italiano</b> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.pt-BR.md">Português</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## Cos'è

OpenPlayer è un lettore video Android open source, senza pubblicità né tracciamento. Offre come software libero le funzioni utili dei più diffusi lettori proprietari: gesti sullo schermo, gestione dei sottotitoli, scelta del decoder, sorgenti di rete e altro. Tutto è scritto da zero.

L'app è disponibile in 12 lingue. Le traduzioni sono automatiche e non sono state riviste da madrelingua; le correzioni sono benvenute.

## Download

Scarica `OpenPlayer.apk` dalla [pagina delle versioni](https://github.com/avsessisozx/Openplayer/releases) e installalo (Android 7.0+). Potrebbe servire l'autorizzazione per le origini sconosciute.

> **Scarica solo dalla pagina delle versioni.** La chiave di firma è pubblica (open source); un APK da altre fonti potrebbe essere una copia manomessa che si installa sopra l'app originale.

> **Stato: beta.** Compilato e testato su CI ed emulatore, ma non ancora ampiamente su dispositivi reali. Segnala i problemi in [Issues](https://github.com/avsessisozx/Openplayer/issues).

## Funzioni

- **Motore di riproduzione:** libmpv (mpv + FFmpeg). Decoder: **HW** (solo hardware, MediaCodec), **HW+** (hardware; altrimenti hardware con copia, poi software), **SW** (solo software, FFmpeg); modificabili durante la riproduzione.
- **Novità (beta23):** sottotitoli ASS/SSA con stile completo (libass); traduzione dei sottotitoli incorporati (MKV/WebM/MP4); modalità notte per l’audio.
- **Riproduzione:** velocità 0,25x–4x, 2x con pressione prolungata, ripetizione A-B, timer di spegnimento, equalizzatore, aumento del volume fino al 200%, ritardo audio, ripresa da dove eri rimasto, PiP, riproduzione in background, blocco schermo e controllo genitori, schermate.
- **360° / VR:** video e foto 360°, sensore di movimento, modalità visore VR.
- **Gesti:** fasce sinistra/centro/destra (luminosità, volume, velocità, ricerca, sottotitoli, zoom), scorrimento orizzontale per cercare, doppio tocco, zoom a pizzico.
- **Sottotitoli:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD e altri; regolazione di ritardo/velocità, rilevamento automatico della codifica, stile e font personalizzato; ricerca su OpenSubtitles.com (serve la tua [chiave API](https://www.opensubtitles.com/consumers)); **traduzione dei sottotitoli** (MyMemory, LibreTranslate, IA compatibile OpenAI).
- **File:** solo cartelle con video, griglia/elenco, ordinamento, cartelle nascoste e privata (bloccata), segnalibri, gestione multimediale.
- **Rete:** SMB, FTP, WebDAV; indirizzi HTTP/HTTPS/RTSP/HLS/DASH.
- **Lettore musicale** (facoltativo): viste per brani, album, artisti e cartelle, coda, equalizzatore, modifica dei tag.

## Limiti noti

I sottotitoli a immagine (VobSub, PGS) sono mostrati da mpv ma non si possono tradurre e non compaiono in modalità 360°; niente Chromecast né deinterlacciamento; i sottotitoli esterni dei video di rete non vengono trovati automaticamente.

## Compilazione

Ogni modifica viene compilata da **GitHub Actions**. In locale: JDK 17, Android SDK e `gradle assembleDebug`.

## Privacy e sicurezza

Nessuna pubblicità, tracciamento o analisi. Le password dei server di rete e di OpenSubtitles sono salvate **non cifrate** nella memoria privata dell'app. `app/openplayer-test.jks` è una chiave di firma di test pubblica. Dettagli: [SECURITY.md](../SECURITY.md).

## Licenza

GNU AGPLv3 ([LICENSE](../LICENSE)). Motore di riproduzione: [mpv](https://mpv.io) (GPL-2.0+), [FFmpeg](https://ffmpeg.org) (GPL-3.0+), [libass](https://github.com/libass/libass) (ISC), [libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1+), [dav1d](https://code.videolan.org/videolan/dav1d) (BSD-2-Clause), FreeType, HarfBuzz, FriBidi, mbedTLS, libxml2, Lua — tramite [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android) (MIT); [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0). Elenco completo, licenze e archivi dei sorgenti: [mpv-licenses.md](mpv-licenses.md).
