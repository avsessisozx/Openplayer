<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Lecteur vidéo et musique gratuit, open source et sans publicité pour Android.<br>Gestes à l'écran, décodeurs HW / HW+ / SW, recherche et traduction de sous-titres, vidéo 360°, lecture réseau SMB / FTP / WebDAV.</b>
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
  <b>Français</b> ·
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

## Présentation

OpenPlayer est un lecteur vidéo Android open source, sans publicité ni suivi. Il propose, sous forme de logiciel libre, les fonctions utiles des lecteurs propriétaires populaires : gestes à l'écran, gestion des sous-titres, choix du décodeur, sources réseau, etc. Tout est écrit de zéro.

L'application est disponible en 12 langues. Les traductions sont automatiques et n'ont pas été relues par des locuteurs natifs ; les corrections sont les bienvenues.

## Téléchargement

Téléchargez `OpenPlayer.apk` depuis la [page des versions](https://github.com/avsessisozx/Openplayer/releases) et installez-le (Android 7.0+). Vous devrez peut-être autoriser les sources inconnues.

> **Statut : bêta.** Compilé et testé sur CI et émulateur, mais pas encore largement testé sur de vrais appareils. Signalez les problèmes dans [Issues](https://github.com/avsessisozx/Openplayer/issues).

## Fonctions

- **Décodeurs :** HW, HW+ (extension FFmpeg : AC3, E-AC3, DTS, TrueHD, FLAC, Opus…) et SW, modifiables pendant la lecture. Moteur libVLC de secours pour les formats qu'ExoPlayer ne lit pas.
- **Lecture :** vitesse 0,25x–4x, 2x par appui long, répétition A-B, minuterie de veille, égaliseur, gain de volume jusqu'à 200 %, décalage audio, reprise là où vous en étiez, PiP, lecture en arrière-plan, verrouillage de l'écran et contrôle parental, captures d'écran.
- **360° / VR :** vidéos et photos 360°, capteur de mouvement, mode casque VR.
- **Gestes :** bandes gauche/centre/droite (luminosité, volume, vitesse, déplacement, sous-titres, zoom), balayage horizontal pour avancer, double appui, zoom par pincement.
- **Sous-titres :** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD et plus ; réglage du décalage/de la vitesse, détection automatique de l'encodage, style et police personnalisée ; recherche sur OpenSubtitles.com (votre propre [clé API](https://www.opensubtitles.com/consumers) est requise) ; **traduction des sous-titres** (MyMemory, LibreTranslate, IA compatible OpenAI).
- **Fichiers :** uniquement les dossiers contenant des vidéos, grille/liste, tri, dossiers masqués et privé (verrouillé), signets, gestionnaire de médias.
- **Réseau :** SMB, FTP, WebDAV ; adresses HTTP/HTTPS/RTSP/HLS/DASH.
- **Lecteur de musique** (facultatif) : vues par titres, albums, artistes et dossiers, file d'attente, égaliseur, édition des tags.

## Limites connues

Pas de rendu ASS/SSA complet (libass) ; les sous-titres image (VobSub, PGS) ne sont pas affichés ; pas de Chromecast ni de désentrelacement ; les sous-titres externes des vidéos réseau ne sont pas détectés automatiquement.

## Compilation

Chaque modification est compilée par **GitHub Actions**. En local : JDK 17, Android SDK et `gradle assembleDebug`.

## Confidentialité et sécurité

Aucune publicité, aucun suivi, aucune analyse. Les mots de passe des serveurs réseau et d'OpenSubtitles sont stockés **en clair** dans l'espace privé de l'application. `app/openplayer-test.jks` est une clé de signature de test publique. Détails : [SECURITY.md](../SECURITY.md).

## Licence

GNU AGPLv3 ([LICENSE](../LICENSE)). Composants utilisés : [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
