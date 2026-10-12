# 1.0.0-beta8

Büyük sürüm. **Cihazda henüz denenmedi** — hata bulursanız lütfen bildirin.

- **Çoklu seçim (MX gibi):** klasöre/videoya uzun bas → seç; hepsini seç, taşı, kopyala, listeye ekle, yeniden adlandır (toplu dahil), sil. Silme çöp kutusuna gider (Ayarlar'dan kapatılabilir).
- **⋮ menüsü:** favori, oynatma listesi, paylaş, yeniden adlandır, altyazı ara, Kes, sesi çıkar (m4a/mp3/ogg, kayıpsız ayıklama), özel klasöre kilitle, ayrıntılı Özellikler paneli, sil.
- **Kes:** kayıpsız kırpma (yeniden kodlama yok, Android 10+).
- **Özel klasör (kasa):** cihaz kilidiyle açılır, ekran görüntüsü engellenir.
- **Favoriler ve oynatma listeleri** (video + müzik); müzikte akıllı listeler (en çok çalınan, son eklenen…).
- **Müzik:** etiket düzenleme (uygulama içi), şarkı sözleri (.lrc/gömülü), kapak değiştirme, hız, "bu şarkı bitince uyu". Müzik başlatma çökmesi düzeltildi.
- **Serbest boyut:** iki parmakla çimdikle klasör, liste ve ızgara simgelerini sürekli büyüt/küçült (yalnız yazıdan yarım ekran karta kadar); ızgarada sütun sayısı değişir. Ayarlar → Liste: yazı boyutu, boyutları sıfırla.
- **360° video:** küresel üstveri otomatik algılanır; sürükle = etrafa bak, çimdik = görüş açısı, çift dokunuş = oynat/duraklat. Algılanmayan dosyalar için ⋮ → "360 olarak oynat" (360°, 180°, üst-alt/yan yana 3B — 3B'de tek göz gösterilir). Deneysel hareket sensörü. Ayarlar → Liste: "360° videoları listede göster" (kapalıysa gizlenir; açıkken "360°" etiketi).
- Videoya özel hafıza, ayarları dışa/içe aktarma, gizli mod.

---

# 1.0.0-beta7

Tek, bütünlüklü sürüm: beta6'daki tema/menü/arayüz modu işleri + aşağıdakiler.

- **Müzik çalar** (Ayarlar → Müzik çalar, isteğe bağlı): ana ekranda "Müzik" sekmesi; şarkılar, albümler, sanatçılar, klasörler; arama ve sıralama; albüm kapakları; "Çalıyor" ekranı (ilerleme, karıştır, tekrar, uyku zamanlayıcısı, ekolayzır/ses artırma, kuyruk); mini oynatıcı; bildirim, kilit ekranı ve Bluetooth düğmeleri. Müzik izni ilk açılışta istenir.
- **Videoya özel hafıza:** hız, görünüm, yakınlaştırma, altyazı gecikmesi/hızı ve ses parçası her video için ayrı hatırlanır (Ayarlar → Oynatıcı'dan kapatılabilir).
- **Ayarları dışa/içe aktar** (Ayarlar ana sayfası → Yedekleme). Parola ve API anahtarı dosyaya yazılmaz.
- Müzikten sonra açılan videoda tekrar/karıştırma kalıntısı temizlenir.

---

# 1.0.0-beta6

- **Tema ve görünüm** (Ayarlar): 9 hazır tema, vurgu rengi, düğme biçimi, menü opaklığı, kısayol boyutu/opaklığı; temayı JSON olarak dışa/içe aktarma.
- Tüm menüler ortak yarı saydam, yuvarlak köşeli panelde; opaklık ayarlanabilir.
- "Ses parçası" menüsü düzeltildi (parçalar seçim, gecikme/ekolayzır düğme). Altyazı menüsü simgeli.
- **Arayüz modu:** Otomatik / Telefon / Büyük telefon / Tablet / Geniş ekran (arayüz ölçeği).
- Doğal sıralama, sessizlikleri atla, gizli mod, geçmişi temizle, "İntro atla" kısayolu, kenar koruması.

---

# 1.0.0-beta5

- **Ses gecikmesi** (± ms) ve **Bluetooth ek gecikmesi**: Ses ayarları → "Ses gecikmesi", ⋮ menüsü. + sesi geciktirir, − öne alır.
- **Altyazı hızı** (kare hızı dönüşümü: 23.976↔25, 24↔25) ve ince ayar: Altyazı → Senkronizasyon.
- **⋮ menüsü** MX düzeninde: şeffaf, sağa yaslı, ince çerçeveli düğmeler.
- **Kısayol satırı** tek sıra, yalnızca simge; ilk 5 düğme ve "›" ile tüm liste.
- **Liste paneli:** Görünüm modu (Klasörler / Dosyalar), düzen, 9 sıralama ölçütü + yön, satırda gösterilecek alanlar, gelişmiş seçenekler.
- **Hızlı yakınlaştırma:** çimdik bırakılınca %100/125/150/200/300/500 adımlarına oturur (Ayarlar → Hareketler).

---

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
