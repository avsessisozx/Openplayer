<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Android için ücretsiz, açık kaynak, reklamsız video ve müzik oynatıcı.<br>Ekran hareketleri, HW / HW+ / SW çözücüler, altyazı arama ve çevirisi, 360° video, SMB / FTP / WebDAV ağ oynatma.</b>
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> ·
  <b>Türkçe</b> ·
  <a href="README.es.md">Español</a> ·
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

## Nedir

OpenPlayer, reklamsız ve izleme içermeyen, açık kaynaklı bir Android video oynatıcısıdır. Popüler kapalı kaynak oynatıcıların kullanışlı özelliklerini (ekran hareketleri, altyazı yönetimi, çözücü seçimi, ağ kaynakları…) özgür yazılım olarak sunmayı amaçlar. Her şey sıfırdan yazılmıştır.

Uygulama 12 dilde kullanılabilir. Çeviriler makine çevirisidir ve ana dili konuşanlarca gözden geçirilmemiştir; düzeltmeler memnuniyetle karşılanır.

## İndirme

[Sürümler sayfasından](https://github.com/avsessisozx/Openplayer/releases) `OpenPlayer.apk` dosyasını indirip kurun (Android 7.0+). "Bilinmeyen kaynaklardan kurulum" izni gerekebilir.

> **Yalnızca Releases sayfasından indirin.** İmza anahtarı herkese açıktır (açık kaynak); başka bir yerden gelen APK, gerçek uygulamanın üstüne kurulan değiştirilmiş bir kopya olabilir.

> **Durum: beta.** CI ve emülatörde derlenip sınandı, gerçek cihazlarda henüz kapsamlı sınanmadı. Hataları [Issues](https://github.com/avsessisozx/Openplayer/issues) bölümünden bildirin.

## Özellikler

- **Oynatma motoru:** libmpv (mpv + FFmpeg). Çözücüler: **HW** (yalnız donanım, MediaCodec), **HW+** (donanım; olmazsa kopyalı donanım, o da olmazsa yazılım), **SW** (yalnız yazılım, FFmpeg); oynatırken değiştirilebilir.
- **Yeni (beta23):** ASS/SSA altyazılar tam stiliyle (libass); gömülü altyazı çevirisi (MKV/WebM/MP4); ses için gece modu.
- **Oynatma:** 0.25x–4x hız, uzun basınca 2x, A-B tekrar, uyku zamanlayıcısı, ekolayzır, %200'e kadar ses artırma, ses gecikmesi, kaldığı yerden devam, PiP, arka planda oynatma, ekran ve çocuk kilidi, ekran görüntüsü.
- **360° / VR:** 360° video ve fotoğraf, hareket sensörü, VR gözlük kipi.
- **Ekran hareketleri:** sol/orta/sağ şeritler (parlaklık, ses, hız, sarma, altyazı, yakınlaştırma), yatay kaydırarak sarma, çift dokunma, çimdikle yakınlaştırma.
- **Altyazı:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD ve daha fazlası; gecikme/hız ayarı, otomatik karakter kodlaması, stil ve yazı tipi; OpenSubtitles.com'da arama (kendi [API anahtarınız](https://www.opensubtitles.com/consumers) gerekir); **altyazı çevirisi** (MyMemory, LibreTranslate, OpenAI uyumlu yapay zekâ).
- **Dosyalar:** yalnızca video içeren klasörler, ızgara/liste, sıralama, gizli ve özel (kilitli) klasör, yer imleri, medya yöneticisi.
- **Ağ:** SMB, FTP, WebDAV; HTTP/HTTPS/RTSP/HLS/DASH adresleri.
- **Müzik oynatıcı** (isteğe bağlı): şarkı, albüm, sanatçı ve klasör görünümleri, sıra, ekolayzır, etiket düzenleme.

## Bilinen eksikler

Resim tabanlı altyazılar (VobSub, PGS) mpv ile gösterilir ama çevrilemez ve 360° kipinde görünmez; Chromecast ve deinterlace yok; ağdaki videolar için yan dosya altyazısı otomatik bulunmaz.

## Derleme

Her değişiklik **GitHub Actions** ile derlenir. Yerelde: JDK 17, Android SDK ve `gradle assembleDebug`.

## Gizlilik ve güvenlik

Reklam, izleme veya analitik yoktur. Ağ sunucusu ve OpenSubtitles parolaları uygulamanın özel alanında **şifrelenmeden** saklanır. `app/openplayer-test.jks` herkese açık bir test imzasıdır. Ayrıntılar: [SECURITY.md](../SECURITY.md).

## Lisans

GNU AGPLv3 ([LICENSE](../LICENSE)). Oynatma motoru: [mpv](https://mpv.io) (GPL-2.0+), [FFmpeg](https://ffmpeg.org) (GPL-3.0+), [libass](https://github.com/libass/libass) (ISC), [libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1+), [dav1d](https://code.videolan.org/videolan/dav1d) (BSD-2-Clause), FreeType, HarfBuzz, FriBidi, mbedTLS, libxml2, Lua — paket: [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android) (MIT); [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0). Bileşen listesi, lisanslar ve kaynak arşivleri: [mpv-licenses.md](mpv-licenses.md).
