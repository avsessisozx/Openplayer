# 1.0.0-beta4

- Ses/parlaklık/sarma göstergelerinin kaybolma süresi milisaniye ayarlı (Ayarlar → Oynatıcı → Görünüm, varsayılan 500 ms).
- Sarma akıcılığı: ExoPlayer sarma modu + daha sık kare güncellemesi.
- Kısayol satırı varsayılan gizli; alt soldaki ayrı düğmeyle açılıp kapanır. Boyut ve opaklık ayarlanabilir.
- Üst/alt şeritler daha şeffaf (karartma yoğunluğu ayarlanabilir).

---

# 1.0.0-beta3

**Yeni:** Sararken canlı önizleme (çubuğu veya ekranı kaydırırken görüntü akar, bırakınca tam konuma gider; Ayarlar → Hareketler → Sarma). Sarma göstergesi `00:27 [+00:09]` biçiminde.

---

# 1.0.0-beta2

**Düzeltmeler:** video küçük resimleri, saydam ses/ilerleme göstergesi, ayna ve dikey çevirme (TextureView), klasöre girişte gecikme (arka plan yükleme), Ayarlar MX tarzı gruplara ayrıldı (Oynatıcı, Hareketler, Video/Senkron, Ses, Altyazı, Çevrimiçi, Liste), ses dili tercihi.

**Güvenlik:** dış adres doğrulaması, altyazı boyut/satır sınırları, güvenli dosya adları, OpenSubtitles yalnızca https, parola/anahtar şifreleme (Keystore), yedekleme kapalı, kimlik bilgisi sızıntısı önlemleri, medya oturumu kısıtlaması, release (debuggable olmayan) derleme, salt okunur CI + SHA-256. Ayrıntı: SECURITY.md

---

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
