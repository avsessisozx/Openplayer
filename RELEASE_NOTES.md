# 1.0.0-beta23

Oynatma motoru baştan değişti: video ve müzik artık **mpv** (libmpv + FFmpeg) ile çalıyor. ExoPlayer ve VLC tamamen kaldırıldı. Derleme ve birim testleri geçti; gerçek cihazda 4K akıcılığı henüz denenmedi.

- **Yeni:** ASS/SSA altyazılar tam stiliyle gösterilir (libass). Gömülü altyazı çevirisi eklendi (yalnız MKV/WebM/MP4; resim altyazılar çevrilemez). Ses ayarlarına gece modu eklendi.
- **Kaldırıldı:** "Akıcı değilse VLC'ye geç" ayarı ve VLC motoru.
- **Değişen:** ASS altyazıda boyut/renk ayarı uygulanmaz. "/" italik işareti gömülü altyazıda çalışmaz.
- **Değişen:** Ekolayzır, bas ve yankı artık yazılım filtresi; ses birebir aynı olmayabilir. Ses artırmada sınırlayıcı yok, çok yüksekte bozulabilir.
- **Değişen:** Müzikte şarkılar arası boşluksuz geçiş yok. Eklenen harici ses dosyası, yeni video açılınca düşer.
- Kaynak kodu: mpv ve FFmpeg GPL lisanslı; kaynak arşivleri bu sürümün dosyaları arasında (`OpenPlayer-native-sources.tar`).

# 1.0.0-beta22

Ağır 4K videolarda yazılım çözücü takılmasına karşı ölçüm ve otomatik geçiş eklendi. Birim testleri ve derleme CI'da geçti; gerçek cihazda (Galaxy Tab A9+) his sende.

- **Akıcı değilse VLC'ye geç** (Ayarlar → Video, varsayılan açık): yerel bir videoda yazılım (FFmpeg) çözücüsüyle kareler birkaç saniye boyunca sürekli atlanıyorsa, oynatma aynı konumdan VLC motorunda devam eder. Oynatma motoru "yalnız yerleşik" seçiliyse devreye girmez.
- **Hata raporuna "Son oynatma" bölümü:** video biçimi (çözünürlük, kodek, bit derinliği, kare hızı, bit hızı), çalışan çözücü, çizilen/atlanan kare sayıları. Takılmayı ekran görüntüsü yerine rapordan teşhis edebilmek için.
