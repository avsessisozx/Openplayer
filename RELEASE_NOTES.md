## OpenPlayer 1.0.0-beta1

İlk kapsamlı sürüm. **Beta:** kod derleniyor ancak gerçek cihazlarda henüz kapsamlı sınanmadı; hataları Issues'tan bildirin.

**Kurulum:** `OpenPlayer-debug.apk` dosyasını indirip kurun (Android 7.0+). Önceki test sürümleri farklı imzayla derlendiği için ilk kez **eski sürümü kaldırmanız** gerekir; bundan sonraki sürümler üzerine güncellenir.

### Neler var
- HW / HW+ (FFmpeg) / SW çözücü modları, hız, A-B tekrar, ekolayzır, ses artırma, gece modu, ayna/çevirme, en-boy oranı, çimdikle yakınlaştırma
- 4 bölgeli ayarlanabilir ekran hareketleri, klavye/fare/medya tuşu desteği
- Altyazı: SRT, VTT, SSA/ASS (renk ve konum), SAMI, MicroDVD ve diğerleri; gecikme ayarı, karakter kodlaması, çevrimiçi arama
- Arka planda oynatma, PiP, ekran kilidi, çocuk kilidi
- Klasör/dosya listesi, arama, sıralama, ızgara görünümü, gizli klasörler, kaldığı yerden devam
- Ağ kaynakları: SMB, FTP, WebDAV ve http/rtsp akışları

### Bilinen eksikler
Tam ASS efektleri (libass), ses gecikmesi ayarı, resim tabanlı altyazılar (VobSub/PGS), Chromecast. Ayrıntılar için README.
