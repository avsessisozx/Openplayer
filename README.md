# OpenPlayer

Android için **açık kaynak, reklamsız** bir video oynatıcı. Amaç: popüler kapalı kaynak oynatıcıların sunduğu kullanışlı özelliklerin
(ekran hareketleri, altyazı yönetimi, çözücü seçimi, ağ kaynakları…) herkesin kullanımına açık, özgür bir karşılığını sunmak.

> Proje adı geçicidir. Hiçbir kapalı kaynak yazılımın kodu, adı, logosu veya tasarım varlığı kullanılmaz; her şey sıfırdan yazılmıştır.

## İndirme

[Sürümler sayfasından](../../releases) `OpenPlayer-debug.apk` dosyasını indirip kurun (Android 7.0+). "Bilinmeyen kaynaklardan kurulum" izni gerekebilir.

> **Durum: beta.** Kod GitHub Actions'ta derleniyor ancak gerçek cihazlarda henüz kapsamlı sınanmadı. Hataları [Issues](../../issues) bölümünden bildirin.

## Özellikler

**Oynatma**
- Üç çözücü modu: **HW** (MediaCodec), **HW+** (MediaCodec + FFmpeg uzantısı: AC3, E-AC3, DTS, TrueHD, FLAC, Opus…), **SW** (FFmpeg tercihli yazılım çözme). Çalışırken değiştirilebilir.
- Hız (0.25x–4x), A-B tekrar, tekrarla/karıştır, uyku zamanlayıcısı, ekolayzır, %200'e kadar ses artırma, gece modu
- Ayna ve dikey çevirme, en-boy oranı seçimi, çimdikle yakınlaştırma ve kaydırma, ekran döndürme kipleri, ekran görüntüsü
- Arka planda oynatma (bildirim ve medya tuşlarıyla), Picture-in-Picture, ekran kilidi ve çocuk kilidi (4 köşeye sırayla dokunma)
- Klavye, fare tekerleği ve Bluetooth/kablolu medya tuşları

**Ekran hareketleri**
- Ekran 4 bölgeye ayrılır (sol/sağ × üst/alt). Her bölgenin dikey kaydırması ayarlardan seçilir: parlaklık, ses, hız
- Yatay kaydırma ile sarma, çift dokunma ile oynat/duraklat ya da ileri/geri atlama

**Altyazı**
- Okunan biçimler: SRT, WebVTT, SSA/ASS (renk, kalın/italik/altı çizili, konum), SAMI, MicroDVD, MPL2, TMPlayer; gömülü altyazı parçaları
- Elle gecikme/öne alma ayarı, otomatik karakter kodlaması (UTF-8 / Windows-1254 yedekli), boyut/renk/arka plan/kenar çizgisi/konum ayarları
- Altyazı hareketleri: yatay kaydırma önceki/sonraki satır, dikey kaydırma konum, çimdik boyut
- Çevrimiçi altyazı arama (OpenSubtitles.com). Ayarlar → Altyazı bölümüne [kendi API anahtarınızı](https://www.opensubtitles.com/consumers) girmeniz gerekir

**Dosyalar**
- Yalnızca video içeren klasörleri listeler; küçük resim, süre, "yeni" ve "izlendi" göstergeleri, sıralama, ızgara/liste görünümü
- Yeniden adlandırma, silme, paylaşma, bilgi, gizli klasörler, kaldığı yerden devam, yer imleri
- **Ağ kaynakları:** SMB, FTP, WebDAV sunucularında gezinme ve oynatma; HTTP/HTTPS/RTSP/HLS/DASH adresleri

## Bilinen eksikler

- **Tam ASS/SSA işleme (libass):** Stil, renk ve konum desteklenir; ancak animasyonlar, gelişmiş efektler, çizim komutları ve gömülü yazı tipleri yoktur.
- **Ses gecikmesi (A/V senkron kaydırma)** henüz yok. Altyazı gecikmesi vardır.
- Resim tabanlı altyazılar (VobSub, PGS) ve Teletext çizilmez.
- Ağdaki videolar için yan dosya altyazısı otomatik bulunmaz; oynatıcıdan elle eklenebilir.
- Chromecast, video kesme, favoriler ve özel çözücü eklentileri yok.
- SMB paylaşım listesi çıkarılamaz; paylaşım adı elle girilir.

## Derleme

Android Studio olmadan: depoya gönderilen her değişiklik **GitHub Actions** ile derlenir, APK "Actions" sekmesinden indirilir.
Yerelde: JDK 17 ve Android SDK ile `gradle assembleDebug` (Gradle 8.14+).

Yeni sürüm yayınlamak için: Actions → "Build APK" → "Run workflow" → sürüm etiketini yazın (örn. `v1.0.0`).

## Gizlilik ve güvenlik notları

- Uygulama reklam, izleme veya çözümleme (analytics) içermez. Ağa yalnızca sizin açtığınız adreslere, altyazı aramaya ve yapılandırdığınız sunuculara bağlanır.
- Ağ sunucusu ve OpenSubtitles parolaları, uygulamanın özel alanında **şifrelenmeden** saklanır.
- `app/openplayer-test.jks` herkese açık bir **test imzasıdır**; sürümlerin birbirinin üstüne güncellenebilmesi için kullanılır. Mağaza yayını için kendi anahtarınızı kullanın.

## Lisans

GNU AGPLv3. Herkes kullanabilir ve değiştirebilir; değiştirilmiş sürümler (ağ üzerinden hizmet olarak sunulsa bile) aynı lisansla, kaynak kodu açık dağıtılmalıdır. Ayrıntılar için `LICENSE`.

Bu proje [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg uzantısı, GPLv3),
[smbj](https://github.com/hierynomus/smbj) (Apache-2.0), [Apache Commons Net](https://commons.apache.org/proper/commons-net/) (Apache-2.0),
[OkHttp](https://square.github.io/okhttp/) (Apache-2.0) ve [Coil](https://coil-kt.github.io/coil/) (Apache-2.0) kullanır.
