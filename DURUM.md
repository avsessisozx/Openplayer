# DURUM — OpenPlayer

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-10-24 · Son sürüm: **v1.0.0-beta13** (2026-10-24, ön sürüm)

## Ne yapıyor
Açık kaynak Android video + müzik oynatıcı. Hedef: MX Player'daki özellikleri kendi kodumuzla sunmak.

## Teknik
- Android, Kotlin, Gradle KTS · `org.openplayer.app` · minSdk 24
- `versionName` elle: `app/build.gradle.kts` (şu an `1.0.0-beta13`)
- Oynatma motoru + libVLC yedek motoru (otomatik geçiş, "Yalnız VLC" seçeneği)
- CI: `build.yml` → `main`'e push'ta yeni sürüm numarası otomatik yayınlanır; `v*` etiketi de yayınlar
- `emulator-test.yml` → `claude/**` dallarında Android 14 emülatör testi; ara sonuçları dala yazar.
  `[ayar]` ile yalnız ayar sayfaları kipi
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
- Testler: birim testleri (SubtitleTranslatorTest, CI'da çalışır); emülatörde çeviri uçtan uca testi
  (sahte OpenAI sunucusu 10.0.2.2:18080) geçti
- beta10, beta11, beta13 emülatör testinden geçti (çökme yok)

## Yarım / sıradaki
- Kullanıcının kararı: önce uygulama özellik açısından tam ve hatasız olacak
- Çoklu dil (uygulama + GitHub sayfası, Türkçe korunarak) **en sona** bırakıldı
- Gerçek cihazda (Xiaomi Mi 10T Pro) kapsamlı deneme henüz raporlanmadı
- Çeviri gerçek servislerle (MyMemory, Groq vb.) denenmedi; yalnız sahte sunucuyla
- Çevrilmiş altyazıda biçim (italik vb.) kaybolur; gömülü altyazı çevrilemez
- Atlananlar: dosya aktarımı, bulut sürücü, kısa videolar; uygulama dili en sona
- Tam (beta olmayan) 1.0.0 sürümü henüz çıkmadı

## Bilinen sorunlar
- Emülatör testi adımı uzun sürüyor (60-80 dk) ve sonda "failure" ile bitiyor; ara sonuç dalı
  (`emulator-results/<id>-ara`) tam yazılıyor, adım hatası sonuç yayınlamayı atlatıyor. Nedeni araştırılmadı.

## Kurallar
- Sürüm vermeden önce emülatörde test et; kullanıcıya bozuk sürüm test ettirme
- Token tasarrufu: kullanıcı maliyetten çekiniyor, gereksiz geniş tarama yapma
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
