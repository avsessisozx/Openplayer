# OpenPlayer

Android için açık kaynak, gelişmiş bir video oynatıcı. Amaç: popüler kapalı kaynak oynatıcıların sağladığı tüm temel özellikleri
(ekran hareketleri, altyazı yönetimi, çözücü seçimi, ağ kaynakları...) reklamsız ve herkesin kullanımına açık sunmak.

> Proje adı geçicidir ve ileride değişebilir. Hiçbir kapalı kaynak yazılımın kodu, adı, logosu veya tasarım varlığı kullanılmaz.

## Durum

`v0.1.0`: iskelet. Çalışan kısımlar:

- Video seçip oynatma (Media3/ExoPlayer)
- Ekran 4 bölgeye ayrılır (sol/sağ × üst/alt). Dikey kaydırma bölgeye atanan işlevi yapar (parlaklık, ses, hız), yatay kaydırma sarar
- Tek dokunma: kontrolleri aç/kapa, çift dokunma: oynat/duraklat

## Yol haritası

1. Hareket ayarları ekranı (bölge işlevlerini seçme), pinch-to-zoom ve pan
2. Altyazı: SRT/ASS/VTT/PGS, elle senkron ayarı, çevrimiçi arama (OpenSubtitles)
3. Ses ve altyazı parçası seçimi, oynatma hızı menüsü, A-B tekrar, en-boy oranı
4. Klasör/dosya listesi, son oynatılanlar, kaldığı yerden devam
5. Ağ kaynakları: HTTP, SMB, FTP, WebDAV
6. Arka planda oynatma, PiP, ekolayzır, zamanlayıcı
7. FFmpeg uzantısı (AC3/DTS vb.), yazılım çözücü seçeneği

## Derleme

Android Studio (Koala veya üstü) ile klasörü açın, ya da:

```
./gradlew assembleDebug
```

## Lisans

AGPLv3. Herkes kullanabilir ve değiştirebilir. Değiştirilmiş sürümler de aynı lisansla, kaynak kodu açık dağıtılmalıdır.

