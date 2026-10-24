# 1.0.0-beta15

360° oynatma, başka oynatıcılardaki eksikler tamamlandı. Android 14 emülatöründe otomatik testten geçti; gerçek cihazda görsel doğrulama sende.

**360° / VR**
- Lens türleri: normal, balık gözü, 360° tam küre, 180° yarım küre. 3B biçimi: yok, yan yana, üst-alt. (Oynatıcı ⋮ → 360 olarak oynat)
- İki parmakla görüntüyü döndürme, "Ufku düzelt", otomatik panorama döndürme (dokununca durur), sensörü dokunarak durdurma, sensör türü 2, sensör açıkken otomatik döndürmeyi kilitleme.
- Uzun basınca çıkan çember: sola/sağa sar, yukarı oynat, aşağı duraklat.
- Ana menü → "360° fotoğraf aç…": 360° fotoğrafları gez.
- Ayarlar → 360° / VR: bu davranışların tümü açılıp kapatılır.

# 1.0.0-beta14

Hata düzeltmeleri ve eksikler. Android 14 emülatöründe otomatik testten geçti.

**Altyazı çevirisi**
- Uzun altyazılar yarıda kesilmez: istekler arasında bekleme eklendi, sunucu "bekle" derse (HTTP 429) söylediği süre kadar beklenir. Hata olsa bile o ana kadar çevrilen satırlar saklanır; tekrar "Altyazıyı çevir" denince kaldığı yerden devam eder.
- Günlük ücretsiz kota dolduğunda açık bir mesaj gösterilir. Google Gemini'nin hata biçimi de doğru okunur.
- Google Gemini hazır ayarı güncel modele (`gemini-3.8-flash`) geçirildi; eski model adı artık "HTTP 404" veriyordu. 404 hatasında "Model listesini getir" önerilir.

**Hata raporu**
- Ayarlar → Hata bildirimi → **Hata raporunu kopyala**: sürüm, cihaz, ayarlar (parola ve anahtarlar hariç), son çeviri günlüğü, son çökme ve uygulama hataları tek metinde panoya kopyalanır. Çeviri hata penceresinde de aynı düğme vardır.

**Listeler**
- Bir klasöre, albüme, çalma listesine veya ağ klasörüne girip geri çıkınca liste artık kaldığı konumda açılır (başa dönmez).
- Ana ekran klasör listesine **sıralama** (ad, en yeni video, video sayısı, boyut) ve **ızgara/liste görünümü** düğmeleri eklendi.

**Oynatıcı**
- Üstteki kısayol satırı: varsayılan olarak ilk 5 simge görünür; parmakla yana çekince yumuşakça kayarak tümü açılır, geri çekince kapanır (ok da çalışır). Oynatıcıdan çıkınca varsayılana döner.
- Kontrol katmanının kenarındaki dikey şerit giderildi (karartma artık tam ekran).

# 1.0.0-beta13

MX Player'ın kütüphane, menü ve ayar ekranları yeniden karşılaştırıldı; eksik kalanlar kendi kodumuzla eklendi. Android 14 emülatöründe otomatik testten geçti; **altyazı çevirisi gerçek servislerle henüz denenmedi** — sorunları Issues'tan bildirin.

**Yeni uygulama ikonu**
- Uygulamanın launcher ikonu yoktu; artık açık halkalı oynat simgesi var. Android 8+ uyarlanabilir ve Android 13+ temalı ikon desteği içerir.

**Altyazı çevirisi**
- Altyazı menüsü → **Altyazıyı çevir…** yüklü altyazıyı seçtiğiniz dile çevirir, yeni bir `.srt` olarak kaydeder ve açar. Zamanlamalar korunur; italik/renk gibi biçimler çevrilen dosyada korunmaz.
- Üç sağlayıcı (Ayarlar → Altyazı çevirisi): **MyMemory** (hesapsız; günde 5.000 karakter, e-posta girilirse 50.000), **LibreTranslate uyumlu adres** ve **OpenAI uyumlu yapay zekâ adresi**. Yapay zekâ için Groq, OpenRouter, Google Gemini ve Ollama hazır ayarları vardır; anahtarı siz girersiniz (cihazda şifrelenir, ayar yedeğine yazılmaz).
- Model listesini sağlayıcıdan getirme ve "Bağlantıyı dene" düğmesi. Uzun altyazılar toplu çevrilir, hız sınırında otomatik yeniden denenir, çeviri iptal edilebilir.

**Medya yöneticisi**
- Ana menü → **Medya yöneticisi:** cihaz depolaması ve video boyutu özeti; son oynatılanlar, son eklenenler, henüz oynatılmayanlar ve büyük dosyalar listeleri.

**Diğer**
- **PiP düğmeleri:** pencere içinde pencerede önceki / oynat-duraklat / sonraki (ya da ±10 sn) düğmeleri (Ayarlar → Oynatıcı).
- **Silerken altyazıyı da sil:** kalıcı silmede videoyla aynı adlı altyazı dosyaları da silinir (çöp kutusunda silinmez).
- **Özel User-Agent** ve ana menüde isteğe bağlı **Çıkış** düğmesi.
- Çeviri mantığı için birim testleri eklendi.

---

# 1.0.0-beta12

Beş yeni özellik. Android 14 emülatöründe otomatik testten geçti; **gerçek cihazda henüz kapsamlı denenmedi** — sorunları Issues'tan bildirin.

**Video / 360°**
- **VR gözlük (Cardboard) modu:** 360°/180° videoda ekran ikiye bölünür ve her göze lens (barrel) distorsiyonu uygulanır; telefon bir VR gözlüğe takılarak izlenebilir. 3B (üst-alt / yan yana) videolarda her göz kendi yarısını alır. "Bakış yönünü sıfırla" artık hareket sensörüyle de çalışır (recenter). 360° menüsünden açılır.

**Ses (video + müzik)**
- **Grafik EQ (10 bant):** cihazın donanım ekolayzırından bağımsız, kendi DSP'imizle (peaking biquad) çalışan 10 bantlı ekolayzır — Ses ayarları → "Grafik EQ".
- **Parametrik EQ:** aynı ekrandaki "Parametrik" seçeneğiyle her bandın frekansı ve Q'su (bant genişliği) de ayarlanabilir.

**Müzik**
- **Etiketleri dosyaya yazma (MP3):** etiket düzenlemede "Etiketleri dosyaya yaz" ile değişiklikler gerçek dosyaya (ID3v2.4) yazılır. Şimdilik yalnızca MP3.
- **.cue desteği:** tek ses dosyasını parçalara bölen .cue dosyaları açılıp çalınabilir (üst bardaki ".cue aç"); oynatma ExoPlayer clipping ile yapılır.

---

# 1.0.0-beta11

MX Player'ın ayar ve menüleri tek tek karşılaştırıldı; eksik kalanlar kendi kodumuzla eklendi. Android 14 emülatöründe otomatik testten geçti.

**Oynatma**
- **Uzun bas = hızlı oynat:** ekrana basılı tutunca video 2x hızlanır, bırakınca normale döner (Ayarlar → Oynatıcı: 1.5x–4x).
- **Kaldığı yerden devam:** Evet / Hayır / **Her seferinde sor**. Listede sonraki videoya geçince de kaldığı yerden devam eder ("Yalnız ilk videoda" seçeneğiyle kapatılabilir).
- **Varsayılan hız** ayarı ve hız menüsünde **Özel…** (0.25x–4x, 0.05 adım). Hız değişikliği artık sonraki videolara taşınmaz.
- **Bitince listeye dön**, **çıkmak için geri tuşuna iki kez bas**, **hatalı videoyu atla** seçenekleri.
- **Uyku zamanlayıcısında özel süre** (1–600 dk); ⋮ menüsüne de eklendi.

**Ekran ve kontroller**
- **Köşede sürekli bilgi:** saat, pil ve/veya geçen süre (kontroller gizliyken).
- Kontroller açıkken **üst çubukta saat ve pil**.
- **±10 sn sarma düğmeleri** (aralık 5–60 sn) ve önceki/sonraki düğmelerini gizleme.
- **Tek dokunma davranışı:** arayüzü göster/gizle, göster sonra duraklat, ikisi birden ya da yalnız oynat/duraklat.
- **Kilitlenince ekran yönü de sabitlenir** (kapatılabilir), **parlaklığı hatırla**, **ekranı açık tut**, **açılışta kontrolleri göster**.
- **Yeni en boy oranları:** Özgün boyut (%100), 1:1, 5:4, 16:10, 18:9, 2.35:1, 2.39:1.

**Ses**
- **Stereo modu:** stereo / mono / **ters stereo** (sol ↔ sağ).
- **Yankı (reverb):** küçük oda … sahne (cihaz desteğine bağlı).
- **Ses dosyası ekle:** videoya ayrı bir ses dosyası (ör. başka dilde dublaj) bağlanır (⋮ → Ses dosyası ekle).
- **Sesi yumuşak başlat:** oynatma başlarken ve sarınca ani ses patlaması olmaz.
- **Tek başına çal** (başka uygulama/arama gelince dur) ve **kulaklık çıkınca duraklat** artık açılıp kapatılabilir.

**Altyazı**
- **Yazı tipi:** Varsayılan, Sans, Serif, Eş aralıklı, Dar, Orta kalın ya da **kendi .ttf/.otf dosyanız**.
- **Kenar:** yok, çizgi, gölge, kabartma, gömme ve **kenar rengi**.
- **Hizalama:** sol / orta / sağ. Daha fazla yazı boyutu ve renk.
- **"/" ile başlayan satırları italik yap**, **varsayılan altyazı gecikmesi**.

**Liste**
- **Her klasörde son izlenen video işaretlenir**; isteğe bağlı olarak klasör açılınca oraya kaydırılır.
- **Aşağı çekerek yenile**, yüzen oynat düğmesini gizleme.
- Ayarlar ana sayfasında **Tüm ayarları sıfırla**.

---

# 1.0.0-beta10

Hata düzeltme sürümü. Android 14 emülatöründe otomatik testten geçti (çökme yok).

- **Altyazı dosyaları yeniden çalışıyor:** .srt/.ass/.sub gibi harici altyazılar Android'de hiç yüklenmiyordu (ayrıştırıcıdaki bir düzenli ifade Android'de hata veriyordu). Videonun yanındaki altyazı artık otomatik açılır.
- **Gömülü altyazı:** dil etiketi olmayan altyazı parçası, otomatik altyazı açıkken seçilir.
- **Bozuk/açılamayan dosya:** ekran sessizce kapanmak yerine "Bu dosya açılamadı" mesajı gösterilir.
- **Paylaş çökmesi giderildi:** dosya yöneticisinden açılan videoyu paylaşmak uygulamayı çökertiyordu.
- **Widget çökmesi giderildi:** ana ekran widget'ı varken müzik çalıp dururken çökebiliyordu.
- **Özel klasör:** kasadaki video oynarken ekran görüntüsü engellenir, geçmişe yazılmaz, bildirimde adı görünmez, paylaşılamaz.
- **Sessize alma:** müzikten sonra açılan videoda sessiz ayarı kendiliğinden geri açılmıyor.
- **Taşı/kopyala/kasaya ekle:** işlem yarıda kalırsa yarım dosya bırakılmıyor.

---

# 1.0.0-beta9

Büyük sürüm. **Cihazda henüz denenmedi** — hata bulursanız lütfen bildirin.

**Müzik**
- **Kitaplık ekranı:** Müzik açılışta Kitaplık ile açılır (Tüm Şarkılar, Klasörler, Klasör Hiyerarşisi, Albümler, Sanatçılar, Albüm Sanatçıları, Tarzlar, Yıllar, Besteciler, Çalma Listeleri). Ayarlar → Müzik'ten kapatılabilir.
- **Sekmeler arası kaydırma:** Listede sağa/sola kaydırınca sekme değişir; sekmelere dokunmak da çalışır.
- **Çimdikle boyut:** Müzik listelerinde iki parmakla simge/yazı boyutu (Ayarlar → Liste'den sıfırlanır).
- **Yeni "Çalıyor" ekranı:** kapak üzerinde favori/kuyruk/⋮ düğmeleri, yuvarlak başlık ve sanatçı etiketleri, kapaktan türeyen arka plan, dalga biçimli ilerleme çubuğu (şarkıya özgü desen; gerçek ses dalgası değildir), ±10 sn sarma, kHz/kbps/biçim bilgisi.
- **Ses:** bas güçlendirme, stereo genişletme, denge (sol/sağ), mono karıştırma, EQ hazır ayarları, ReplayGain (yalnız ses kısar), geçişte yumuşatma (şarkıları üst üste bindirmez). Bas/genişletme cihazın efekt desteğine bağlıdır.
- **M3U / M3U8 / PLS** listeleri içe/dışa aktarma; **albüm kapağını internetten bul** (iTunes Search, onayla); ana ekran **widget'ı**.

**Video**
- **VLC yedek motoru:** ExoPlayer açamazsa libVLC ile açılır (Ayarlar → Oynatıcı → Oynatma motoru: Otomatik / Yalnız ExoPlayer / Yalnız VLC). VLC ekranı sadedir. Uygulama boyutu büyüdü; x86_64 (emülatör) desteği kaldırıldı.
- **Çözücü zinciri:** açılamayan videoda HW → HW+ → SW sormadan, ayarınız değişmeden denenir.
- **Bölümler:** MKV ve MP4 (Nero chpl) bölümleri; ⋮ → Bölümler, çubukta işaretler.
- **Döndür düğmesi** tek basışta dikey↔yatay çevirir; **kısayol satırı** sürükleyerek açılır/kapanır.
- **Jest bölgeleri:** sol/orta/sağ şeritler, sürüklenebilir sınırlar; işlevler: parlaklık, ses, hız, sarma, altyazı konumu/boyutu, yakınlaştırma.
- **Sarma:** "Bırakınca tam konuma git" seçeneği (kapalıysa daha akıcı), daha seyrek arama isteği.

Bilinen sınırlar: parametrik 64 bant EQ, bit-perfect/USB DAC, .cue (müzik) ve dosyaya gerçek etiket yazma henüz yok.

---

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
