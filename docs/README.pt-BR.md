<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Reprodutor de vídeo e música gratuito, de código aberto e sem anúncios para Android.<br>Gestos na tela, decodificadores HW / HW+ / SW, busca e tradução de legendas, vídeo 360°, reprodução em rede SMB / FTP / WebDAV.</b>
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
  <a href="README.de.md">Deutsch</a> ·
  <b>Português</b> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## O que é

OpenPlayer é um reprodutor de vídeo Android de código aberto, sem anúncios nem rastreamento. Oferece, como software livre, os recursos úteis dos reprodutores proprietários populares: gestos na tela, gerenciamento de legendas, escolha do decodificador, fontes de rede e mais. Tudo foi escrito do zero.

O app está disponível em 12 idiomas. As traduções são automáticas e não foram revisadas por falantes nativos; correções são bem-vindas.

## Download

Baixe o `OpenPlayer.apk` na [página de versões](https://github.com/avsessisozx/Openplayer/releases) e instale (Android 7.0+). Pode ser necessário permitir a instalação de fontes desconhecidas.

> **Baixe apenas da página de versões.** A chave de assinatura é pública (código aberto); um APK de outro lugar pode ser uma cópia adulterada que se instala por cima do app original.

> **Status: beta.** Compilado e testado em CI e emulador, mas ainda sem testes amplos em dispositivos reais. Relate problemas em [Issues](https://github.com/avsessisozx/Openplayer/issues).

## Recursos

- **Decodificadores:** HW, HW+ (extensão FFmpeg: AC3, E-AC3, DTS, TrueHD, FLAC, Opus…) e SW, alternáveis durante a reprodução. Motor libVLC de reserva para formatos que o ExoPlayer não abre.
- **Reprodução:** velocidade 0,25x–4x, 2x com toque longo, repetição A-B, temporizador de desligamento, equalizador, aumento de volume até 200%, atraso de áudio, retomar de onde parou, PiP, reprodução em segundo plano, bloqueio de tela e controle dos pais, capturas de tela.
- **360° / VR:** vídeos e fotos 360°, sensor de movimento, modo óculos VR.
- **Gestos:** faixas esquerda/centro/direita (brilho, volume, velocidade, avanço, legenda, zoom), deslizar na horizontal para avançar, toque duplo, zoom com pinça.
- **Legendas:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD e outras; ajuste de atraso/velocidade, detecção automática de codificação, estilo e fonte própria; busca no OpenSubtitles.com (é preciso sua própria [chave de API](https://www.opensubtitles.com/consumers)); **tradução de legendas** (MyMemory, LibreTranslate, IA compatível com OpenAI).
- **Arquivos:** só pastas com vídeos, grade/lista, ordenação, pastas ocultas e privada (bloqueada), marcadores, gerenciador de mídia.
- **Rede:** SMB, FTP, WebDAV; endereços HTTP/HTTPS/RTSP/HLS/DASH.
- **Player de música** (opcional): visões por músicas, álbuns, artistas e pastas, fila, equalizador, edição de tags.

## Limitações conhecidas

Sem ASS/SSA completo (libass); legendas em imagem (VobSub, PGS) não são exibidas; sem Chromecast nem desentrelaçamento; legendas externas de vídeos em rede não são encontradas automaticamente.

## Compilação

Cada alteração é compilada pelo **GitHub Actions**. Localmente: JDK 17, Android SDK e `gradle assembleDebug`.

## Privacidade e segurança

Sem anúncios, rastreamento ou análises. As senhas de servidores de rede e do OpenSubtitles ficam **sem criptografia** no armazenamento privado do app. `app/openplayer-test.jks` é uma chave de assinatura de teste pública. Detalhes: [SECURITY.md](../SECURITY.md).

## Licença

GNU AGPLv3 ([LICENSE](../LICENSE)). Componentes usados: [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
