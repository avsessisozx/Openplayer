# DURUM — OpenPlayer

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-10-24 · Son sürüm: **v1.0.0-beta15** (2026-10-24, ön sürüm)

## Ne yapıyor
Açık kaynak Android video + müzik oynatıcı. Hedef: MX Player'daki özellikleri kendi kodumuzla sunmak.

## Teknik
- Android, Kotlin, Gradle KTS · `org.openplayer.app` · minSdk 24
- `versionName` elle: `app/build.gradle.kts` (şu an `1.0.0-beta15`)
- Oynatma motoru + libVLC yedek motoru (otomatik geçiş, "Yalnız VLC" seçeneği)
- CI: `build.yml` → `main`'e push'ta yeni sürüm numarası otomatik yayınlanır; `v*` etiketi de yayınlar
- `emulator-test.yml` → `claude/**` dallarında Android 14 emülatör testi; ara sonuçları dala yazar.
  `[ayar]` ile yalnız ayar sayfaları, `[ray]` ile yalnız kısayol satırı kipi (kısa, ~15 dk)
  Commit mesajına bu etiketler yazılınca o kip koşar.
- Sürüm notları: `RELEASE_NOTES.md` (Türkçe)

## Yapılanlar
- beta4–beta9: müzik kitaplığı, çalıyor ekranı, sekmeler, çimdikle boyut, widget, özel klasör (kasa)
- beta10: altyazı ayrıştırıcı Android düzeltmesi, paylaş/widget çökmeleri, bozuk dosya mesajı
- beta11: MX Player ayarları karşılaştırıldı; uzun bas hızlı oynat, kaldığı yerden devam,
  özel hız, ±10 sn, köşe bilgisi, stereo/ters stereo, reverb, harici ses dosyası, altyazı
  yazı tipi/kenar/hizalama, son izlenen işareti, tüm ayarları sıfırla
- beta12: VR gözlük kipi, grafik/parametrik EQ, dosyaya etiket yazma, .cue
- beta13: yeni uygulama simgesi (uyarlanabilir + eski PNG), altyazı çevirisi (MyMemory anahtarsız /
  LibreTranslate uyumlu / OpenAI uyumlu adres; Groq, OpenRouter, Gemini, Ollama hazır ayarları;
  çıktı `<video>.<dil>.srt`), medya yöneticisi (depolama özeti, akıllı video listeleri),
  PiP düğmeleri (play/pause + önceki/sonraki veya ±10 sn), altyazıyla birlikte silme, çıkış düğmesi,
  HTTP User-Agent ayarı
- beta14: altyazı çevirisi bekleme/devam (429 bekleme, yarım kayıt, kota mesajı), "Hata raporunu kopyala"
  düğmesi (yalnız OpenPlayer), listeler girip çıkınca konumunu korur, ana ekran klasör sıralama + ızgara,
  Gemini model adı güncellendi, kenardaki dikey şerit giderildi
- beta15: 360°/VR: lens türleri (normal/balık gözü/360/180), 3B biçimleri, ufuk düzeltme, otomatik döndürme,
  uzun basınca çember, 360° fotoğraf ekranı; hareket sensörü + VR gözlükteki siyah ekran düzeltildi
  (baş takibi `SensorView.kt`, birim testli); kısayol satırı: varsayılan 4 simge, çekince açılır/kapanır,
  boş alan tepkisiz, yeni videoda sıfırlanır; sürüm notu yalnız yeni sürümün bölümü
- Testler: birim testleri (SubtitleTranslatorTest, CI'da çalışır); emülatörde çeviri uçtan uca testi
  (sahte OpenAI sunucusu 10.0.2.2:18080) geçti
- beta10, beta11, beta13 emülatör testinden geçti (çökme yok)
- beta15 doğrulama (2026-10-24): birim testleri + derleme + lint geçti; emülatörde 360 akışları çökmesiz,
  sensör/VR gözlük görüntüsü siyah değil (parlaklık ölçümü), kısayol satırı simge sayısı 5→15→5 (kısa mod).
  **Tam emülatör koşusu iki kez sonuçsuz öldü** (71 dk), bu yüzden çeviri uçtan uca testi, ayar sayfası
  gezintisi ve monkey beta15'te koşmadı; beta14'ten beri o alanlar değişmedi.

## Çoklu dil görevi — ENGELLENDİ (2026-10-25)
- Zamanlanmış çalıştırma başladı ama Telemet reposu bu oturumda erişilemez (yalnız avsessisozx/openplayer tanımlı; add_repo/list_repos araçları yok).
- Telemet'in dil listesi ve README düzeni bilinmeden başlanmadı; dil listesini tahmin etmedim. Koda, README'ye, sürüme dokunulmadı.
- Gerekli: oturuma Telemet erişimi verilmesi (veya dil listesinin elle bildirilmesi), sonra görev yeniden çalıştırılır.

## Yarım / sıradaki
- Kullanıcının kararı: önce uygulama özellik açısından tam ve hatasız olacak
- Çoklu dil (uygulama + GitHub sayfası, Türkçe korunarak) **en sona** bırakıldı
- Gerçek cihazda (Xiaomi Mi 10T Pro) kapsamlı deneme henüz raporlanmadı; özellikle beta15'te doğrulanmayanlar:
  gerçek 360° videoda baş takibi / VR gözlük hissi (emülatörde sanal sensör), kısayol satırı parmakla his
- Kısayol satırı tamamen açıkken ekrana sığmayabilir (dar ekranda kaydırılır); ok sağda kalır
- Sürüm notları GitHub'da beta4–beta14 için birikmiş halde duruyor; her sürüme yalnız kendi notunu koymak
  için hazır dosyalar kullanıcıya verildi (oturum sürüm düzenleyemiyor)
- Çeviri gerçek servislerle (MyMemory, Groq vb.) denenmedi; yalnız sahte sunucuyla
- Çevrilmiş altyazıda biçim (italik vb.) kaybolur; gömülü altyazı çevrilemez
- Atlananlar: dosya aktarımı, bulut sürücü, kısa videolar; uygulama dili en sona
- Tam (beta olmayan) 1.0.0 sürümü henüz çıkmadı

## Bilinen sorunlar
- Tam emülatör testi uzun sürüyor (60-75 dk) ve sonda "failure" ile bitiyor; iş günlüklerine oturumdan
  erişilemiyor (403), nedeni görülemedi (runner kapanıyor olabilir). Ara sonuç dalı (`emulator-results/<id>-ara`)
  yalnız "temel akışlar", "360" ve ayar sayfası kontrol noktalarını içeriyor; özellik testleri bitmeden sonuç yok.
  Önlem: `[ray]`/`[ayar]` kısa kipleri, ayrı kontrol noktaları.
- Oturum GitHub sürümlerini düzenleyemez/oluşturamaz; sürümü `main`'e push edince `Build APK` yayınlar.

## Kurallar
- Sürüm vermeden önce emülatörde test et; kullanıcıya bozuk sürüm test ettirme
- Token tasarrufu: kullanıcı maliyetten çekiniyor, gereksiz geniş tarama yapma
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
- Test geçip CI yeşilse sürümü `main`'e gönder (tek commit); tekrar onay sorma
- Büyük/uzun işten önce kısaca ne yapılacağını söyle ve "Başlayayım mı?" diye sor
- Sürüm özeti kısa (~10 satır); DURUM.md'ye doğrulanan ve doğrulanmayanı yaz
- Android'e eklenen düzeltme/özellik Windows sürümü olan uygulamalarda ikisine de eklenir (OpenPlayer yalnız Android)
