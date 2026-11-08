# DURUM — OpenPlayer

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-11-07 · Son sürüm: **v1.0.0-beta22** (2026-11-07, ön sürüm)

## Devam noktası (mpv geçişi)
> Kota dolup devam edilirse: önce `git fetch --all`, bu bölümü ve `claude/mpv-*` dallarını oku; bitenleri yeniden yapma.
Güncelleme: 2026-11-07 · Plan: yerel `OpenPlayer-mpv-TEK-PROMPT.md` (hedef sürüm 1.0.0-beta23; ara sürüm yok)
- **Biten faz:** Faz 0 bitti (G). F'nin başlangıç görevi bitti. **Faz 1 bitti ve KABUL** (G, claude/mpv-ana d457a2a). 2026-11-08 00:48: kota + oturum kesintisinden sonra tüm işçiler kaldıkları yerden sürdürüldü.
- **Ara uçlar (00:48):** ana 396572c (Faz 1 ara), a f96c043 (push yok, wt-a'da yarım), b a438c29, c 42c75c3 (ara), d e88e0d9, e 3d145b3, f bfd17ff. Hiçbiri henüz yönetici tarafından doğrulanmadı.
- **Karar:** AAR `io.github.abdallahmehiz:mpv-android-lib:0.1.12` + `kotlinx-coroutines-android:1.10.2`. 360° SurfaceTexture yolu çalışıyor, alternatif gerekmiyor. Lisans uyumlu: ikili GPLv3, AGPLv3 ile birleşebilir (hukuki görüş değil).
- **Yayın öncesi zorunlu:** mpv/FFmpeg vb. karşılık gelen kaynak sunulmalı; uygulama içinde lisans bildirimi + GPLv3 metni olmalı (`docs/mpv-licenses.md`).
- **Dallar/işçiler:** `claude/mpv-ana` (G, Faz 1), `claude/mpv-f` (F bitti, bfd17ff; birleştirilecek; sonda yeniden çağrılacak). `claude/mpv-a/b/c/d/e` f96c043'ten başlıyor (A altyazı, B ses, C ağ, D müzik/sistem, E 360°).
- **Doğrulanan:** f96c043 yönetici tarafından yerelde derlendi + birim test geçti. CI emülatöründe (x86_64, API 34) mpv yükleme, görüntü, https, `af` filtreleri (prob 9/9 çökmesiz). Yerel emülatörde SurfaceTexture → OES.
- **Doğrulanmayan:** gerçek cihaz, akıcılık, 4K, API 24, ses kalitesi kulakla, HLS/DASH/RTSP canlı akış.
- **C (ağ, Faz 4) KABUL — claude/mpv-c 1ac3d6d:** yönetici yerelde doğruladı: yalnız kendi 4 dosyası, derleme + lint yeşil, LocalStreamProxyTest 30/30, NetworkSourcesTest 15/15 (sahte FTP/WebDAV). Doğrulanmayan: SMB (sahte sunucu yok), mpv'nin vekile gerçek davranışı, gerçek NAS, HLS/DASH/RTSP. Sınır: vekil koparsa kendisi yeniden denemez; commons-net FTP API 24/25'te çalışmayabilir (eskiden de). G'ye bağlama: `engine.open(NetworkPlayback.prepare(ctx, engine, uri))`, oturum sonunda `NetworkPlayback.stop()`, hata metni önce `NetworkPlayback.lastError`, özgün adres korunur, hata raporuna mpv `path` yazılmaz (jeton), `tls-verify=yes` + `tls-ca-file` G'nin işi, `networkdat_003` silinebilir; MPV-REMOVE: `NetworkDataSourceFactory` + 2 import (PlayerHolder ile silinir).
- **D (müzik/sistem, Faz 5) KABUL — claude/mpv-d 362213b:** yönetici yerelde doğruladı: yalnız kendi dosyaları (+ CueSheet.kt'de bir yorum), derleme + lint yeşil, yeni testler 29/29 (MusicDeck 11, MusicQueue 6, Chapters 5, MediaTags 3, AudioFocusPolicy 4). Framework MediaSession + MediaStyle, uygulama tarafı sıra, PlaybackHub (ses odağı, kulaklık çıkınca durma), bölümler mpv chapter-list. Doğrulanmayan: cihaz/emülatörde hiçbir şey (bildirim, kilit ekranı, medya tuşları, Bluetooth, widget, arka plan). **Değişen davranış:** dosyalar arası boşluksuz geçiş (gapless) yok (yalnız aynı dosyadaki .cue parçaları boşluksuz); uyku zamanlayıcısı artık video çalarken de duraklatır. G'ye bağlama: manifest servis girdisi (`foregroundServiceType=mediaPlayback`, media3 intent-filter silinir), PlayerActivity `PlaybackTarget` + `PlaybackHub.setTarget`, onStop'ta `PlaybackService.start`, bölümler `Chapters.read(engine)`; MPV-REMOVE: `MusicController.isMusic`, `Chapters.load`. B'ye: AudioFx panel API'si korunacak, `volume` müzikte MusicController'da.
- **B (ses, Faz 3) KABUL — claude/mpv-b 1ade555:** yönetici doğruladı: yalnız kendi dosyaları, derleme + lint yeşil, MpvAudioTest 37/37. Tek `MpvAudio.apply`; perde asetrate+atempo (hıza dokunmaz), pan, equalizer, acompressor, aecho; %200 = +15 dB. **Değişen:** cihaz EQ/bas/genişlik/yankı artık mpv yazılım filtresi (her cihazda çalışır, ses birebir aynı değil); ses artırmada limiter yok (çok yüksekte kırpılabilir); harici ses dosyası yeni dosya açılınca düşer. Yeni: gece modu (`audiofx_night`). Doğrulanmayan: kulakla kalite, tık sesi, cihaz.
- **E (360°, Faz 6) KABUL — claude/mpv-e 3d145b3:** yönetici doğruladı: yalnız Vr360.kt (+1 satır Vr360PhotoActivity) ve test, derleme + lint yeşil, Vr360OutputTest 13/13. Emülatörde gerçek mpv ile normal↔360 geçişleri, 10-bit HEVC renkleri doğru. Doğrulanmayan: hwdec=mediacodec (emülatörde ikinci açılışta kare gelmedi, normal yüzeyde de), 4K akıcılık, baş takibi cihazda. Risk: her yüzey değişimi çözücüyü baştan başlatıyor → 4K HEVC'de 360 giriş/çıkış ve duraklatmada birkaç sn siyah olabilir.
- **A (altyazı, Faz 2) KABUL — claude/mpv-a 5ed5ff6:** yönetici doğruladı: yalnız kendi dosyaları, derleme + lint yeşil, EmbeddedSubtitlesTest 12, SubtitleControllerTest 11, SubtitleTranslatorTest 17 geçti. libass, `sub-ass-override=yes`, gömülü altyazı çıkarma (MKV/WebM kendi okuyucu, MP4 MediaExtractor; 34 parçalı 1,5 GB MKV 5,6 sn). **Değişen:** ASS altyazıda boyut/renk ayarı uygulanmaz; "/" italik gömülüde çalışmaz; AVI/TS gömülü altyazı çevrilemez.
- **Birleştirme denemesi (A+B+C+D+E):** dosya çakışması yok, ana kod derleniyor; A'nın 5ed5ff6 düzeltmesiyle A+B+C+D+E birlikte derleme + lint yeşil, 14 test sınıfı / 172 test, hata 0 (yönetici, yerel).
- **Kararlar (yönetici):** müzikte `volume`'u MusicController yönetir, `MpvAudio.volumeManagedElsewhere=true`; tutarlılık için MusicController `MpvAudio.volumeFor` (public yapılacak) ile kübik eğri kullanır. 360'ta altyazı `subtitles.overlayOnly=true` (A) — E'nin önerisiyle aynı.
- **G Faz 1 KABUL — d457a2a:** yönetici doğruladı: derleme + lint + birim test yeşil; CI emülatör [ray] 471e193 ve e265af5 success (ilk koşuda şerit 0 okudu, ikincide 14/14 ok; beta17'deki kararsızlıkla aynı). MpvPlayer + VideoPlayer kuyruğu, PlayerActivity SurfaceView; PlayerHolder, VlcPlayerActivity, akıcılık bekçisi, VLC ayarları silindi; lisans `assets/licenses/` (THIRD_PARTY_NOTICES, GPL-3.0, Apache-2.0); hata raporuna mpv bilgisi (`path` yok). Geçici: `LegacyMusicBridge.kt` (müzik eski ExoPlayer'da, D ile silinir), 7 MPV-REMOVE, Media3 gradle'da (Faz 7). Spike kanıtı `claude/mpv-spike` 65006eb. Doğrulanmayan: gerçek cihaz, 4K, ses parçası menüsü, döndürme, harici ses, ekran görüntüsü.
- **Birleştirme denemesi G + A–E:** tek çakışma `SubtitleController.kt` (G'nin köprüleri) → A'nın sürümü esas. Diğer dallar temiz.
- **Birleştirme (2026-11-08 01:27):** A–E `claude/mpv-ana`'ya birleşti (97a004f…7e77157). Bağlama notları + Faz 7 G'de; 01:3x'te haftalık kullanım sınırıyla kesildi, commit'lenmiş bağlama işi yok. 19:01'de G sürdürüldü.
- **Bağlama + Faz 7 (19:04–19:08, G):** 2714251 bağlama (12 dil metinler), 3a4e86b Media3/nextlib/libVLC kaldırıldı + beta23 (versionCode 22). **Yönetici bağımsız doğrulaması (2bafe21):** `grep -rniE "media3|exoplayer|nextlib|videolan|libvlc" app/ build.gradle.kts settings.gradle.kts gradle/ --exclude-dir=build` → 0; MPV-REMOVE 0; LegacyMusicBridge silinmiş; debug+release derleme yeşil; 15 sınıf / 178 test hata 0; lint 5 Error (eb6c058'den beri var, mpv dışı) + 302 uyarı; release APK 42,5 MB, .so: libmpv, libplayer, libav*, libsw*, libc++_shared, libxml2 (arm64-v8a + armeabi-v7a), libvlc/libffmpegJNI yok; dex'te Media3/ExoPlayer/videolan/nextlib sınıfı 0, `is.xyz.mpv.MPV` var. (libavutil.so içinde FFmpeg'in kendi "videolan" metni bir kez geçiyor; libVLC değil.) CI [ray] + [ayar] koşuyor. F son görev: kaynak sunumu + sürüm notu (`claude/mpv-f2`).
- **F son görev KABUL — claude/mpv-f2 ed10163:** yönetici doğruladı: yalnız workflows/docs/README/RELEASE_NOTES. GPL kaynak sunumu: `native-sources.yml` 20 arşivi (binary'deki kesin commit'ler) `OpenPlayer-native-sources.tar` yapıp Release'e ekliyor; kaynak inmezse sürüm çıkmaz; AAR sürümü listeyle uyuşmazsa hata. Koşular: Native sources 42499455457 success, release dry-run 42499597496 success (gh release create koşulmadı). README 12 dil mpv'ye göre güncellendi (grep exoplayer/media3/nextlib/vlc → 0). Sürüm notu beta23 (6 madde). Repo açıklaması + konu etiketleri yayınla birlikte yönetici yazacak (kullanıcı GitHub sayfasının güncellenmesini istedi). CI emülatör: [ray] 7a7e60f success (42498359284), [ayar] 2bafe21 koşuyor (42498382922).
- **Plan düzeltmesi:** rubberband yok (perde: asetrate+aresample+atempo); kare sayacı `frame-drop-count`.
- **Sıradaki adım:** G, A–E'yi `claude/mpv-ana`'ya birleştirip bağlama notlarını uygular + Faz 7 temizliği; sonra F son görev (grep kanıtı, APK içeriği, sürüm notu), tam emülatör, yayın kapısı.

## Ne yapıyor
Açık kaynak Android video + müzik oynatıcı. Hedef: MX Player'daki özellikleri kendi kodumuzla sunmak.

## Teknik
- Android, Kotlin, Gradle KTS · `org.openplayer.app` · minSdk 24
- `versionName` elle: `app/build.gradle.kts` (şu an `1.0.0-beta21`)
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

## beta16 — çok dilli destek (2026-10-25)
- 12 dil: İngilizce (varsayılan), tr, es, fr, it, de, pt-BR, zh-CN, ja, ko, ru, ar. Telemet'in dil listesi baz alındı.
- Metinler `Tx.s(R.string.…)` ile; katalog `tools/i18n/*.py` (1080 anahtar), `tools/i18n/build.py` `values*/strings.xml` üretir
  (`STRICT=1`), `merge.py <dil>` parçaları doğrular. Dil başına 3 parça dosya.
- README İngilizce; diğer diller `docs/README.<kod>.md`.
- **Doğrulandı:** birim testleri (StringsResourceTest: tüm dillerde yer tutucular), derleme, lint, hızlı emülatör (çökme 0).
- **Doğrulanmadı:** tam emülatör koşusu 2 kez sonuçsuz öldü (~53 dk, günlük erişimi yok); emülatörde TR dışında dil
  denenmedi (uzun metinli dillerde taşma olabilir); gerçek cihaz yok; çeviriler makine çevirisi, ana dili konuşan gözden geçirmedi;
  mantık anahtarı olarak saklanan/karşılaştırılan metinler ve sınıf yüklenirken dil yakalayan sabitler taranmadı.
- Repo açıklaması ve konu etiketleri API ile yazılamadı (ortam izin vermiyor); kullanıcı elle girmeli.

## beta17 (2026-10-25)
- 360° sürükleme yönü düzeltildi (`Vr360.look`: GestureDetector dx işareti), kısayol satırı: eşik kayıpsız 1:1 izleme + hafif çapraz sürükleme, MX tarzı klasör ağacı (`VideoRepository.treeFolders`, `MainActivity.treeDir`).
- **Doğrulandı:** birim testleri, derleme, lint, kısa emülatör [ray]: yavaş sürüklemede satır parmakla açılıyor (5→9→15), klasör ağacı Movies>Test>Alt>Derin gezinti + geri. Bir koşuda satır testi 0 okudu (kararsız UI dökümü), tekrarda geçti.
- **Doğrulanmadı:** gerçek parmakla his, 360° yönü gerçek videoda (kod mantığı + işaret analizi), tam emülatör turu.
- Ağaçta üst klasörlerde seçim/gizleme/yeniden adlandırma yok (yalnız videoyu doğrudan içeren klasörlerde).

## beta18 + beta19 (2026-10-25)
- beta18: altyazı metni üstünde sola kaydır = sonraki, sağa = önceki satır (`GestureController`, alan altyazı konumuna göre genişledi); "Bir altyazı satırıyla eşleştir…" (`PlayerMenus.showSyncPick`, gecikme = video zamanı − satır/hız); çember yeniden çizildi (`VrWheel.kt`); kalan sabit metinler çevirilebilir yapıldı (sn → s).
- beta19: ⋮ → "Kare kare ve perde" (`stepFrame`: duraklat + kare süresi kadar EXACT seek; `setPitchSemitones`: ±12 yarım ton, hız değişmez).
- **Doğrulandı (emülatör [ray]):** satır eşleştirme listesi/seçim, çember çökmeden çizildi (ekran görüntüsü incelendi), kare/perde paneli açıldı, perde etiketi +1.
- **Doğrulanmadı:** kare ilerlemenin gerçek görüntüde tam 1 kare olduğu, perdenin kulakla duyulması, çemberin 360° videoda gerçek parmakla hissi, tam emülatör turu. Tüm yeni metinler makine çevirisi.
- Kalan piyasa eksikleri: Chromecast/DLNA, Android TV, GIF/klip dışa aktarma, Trakt/sesli komut.

## beta20 (2026-10-26)
Kaynak: kullanıcının MX Player ekran kaydı + OpenPlayer hata raporu (çökme yok, davranış farkı). MX kare kare incelendi.
- Altyazı: parmak değince gri vurgu kutusu (`SubtitleController.setGrab`); dikey sürükleme konumu (önceden vardı); yatay kaydırmada metin parmakla gelir, komşu satır ikinci görünümden (`ghostView`, yalnız harici altyazı) yandan girer,
  bırakınca >60 dp ise `jumpSubtitle`, değilse yerine döner (`PlayerActivity.subtitleSwipeMove/End`). Parmak altyazıdayken uzun basış kapalı (`GestureController.subtitleHit`): tutup sürüklemek mümkün; eskiden tutunca kaydırma yok sayılıyordu.
- Kısayol şeridi MX gibi yeniden yazıldı (`PlayerActivity.RayScroll`, FrameLayout): son 4 simge görünür, öncekiler + "Öğeleri özelleştir" solda gizli; parmak sağa = açılır, sola = kapanır, şerit parmağı birebir izler;
  açıkken simge altında etiket; taşan kısım serbest kayar, kapanma aralığında en yakın uca oturur; ok düğmesi aç/kapa. `Shortcut` enum sırası MX'e çevrildi (Hız, Arka planda oynat, Ekran döndürme, Tekrarla sağda).
- **Doğrulandı (emülatör [ray], 3. koşu):** birim testi, derleme, lint; şerit simge sayısı 5 → 15 → 5, yavaş çapraz sürükleme 5 → 9 → 15, boş alan tepkisiz; altyazı vurgu kutusu ve yatay kaydırmada metnin parmakla kayması (ekran görüntüsü), dikey sürükleme konumu; çökme yok.
- **Doğrulanmadı:** gerçek parmakla his (şerit hızı/eşik, altyazı kaydırma eşiği 60 dp), komşu satırın önizlemesi (testte son satır olduğu için görünmedi), gömülü altyazıda önizleme yok (komşu satır bilinmiyor), etiketlerin dar ekranda taşması, tam emülatör turu.
- Not: beta20 öncesi ilk iki koşuda şerit 0 okudu: 1. koşuda (neden: aynı), 2. koşuda 6 okudu (sol iç boşluk gizli simgenin kenarını gösteriyordu) → boşluk kaldırıldı.

## beta21 (2026-11-06)
İstek: yerel videoda sarma akıcılığı, online takılma/ön bellek, Galaxy Tab A9+ (SD695) gibi cihazlarda 4K/360° için yazılımla yapılabilecekler.
Bulgu: kodek paketi (nextlib FFmpeg), libVLC yedeği, çözücü zinciri (HW→HW+→SW→VLC) ve 360° (`Vr360`) zaten vardı; WebView hiç yok.
- `PlayerHolder`: `DefaultLoadControl` (20–60 sn tampon, başlama 1,5 sn, yeniden başlama 3 sn, 15 sn geri tampon → kısa geri sarma anında).
- `PlayerActivity.seekRelative`: 700 ms içinde art arda ±sn dokunuşunda `CLOSEST_SYNC`, seri bitince 900 ms sonra eski ayar; `scrubMove` aralığı ≥2000 px yükseklikte 40→90 ms.
- Performans modu (`PerformanceBoost.kt`, Ayarlar → Video, varsayılan kapalı): `setSustainedPerformanceMode` + `PerformanceHintManager` (API 31+). Donanım sınırını aşmaz.
- Çözücü hataları (EXCEEDS_CAPABILITIES, FORMAT_UNSUPPORTED, DECODER_INIT_FAILED, DECODING_FAILED) için son mesajda video biçimi + ne denenebileceği (`playeracti_hwlimit`, 12 dil).
- **Doğrulandı:** derleme + birim testleri (CI), emülatörde "videolar" kontrol noktası (53 ekran, çökme yok; HEVC 10-bit, mkv, wmv açma/sarma).
- **Doğrulanmadı:** gerçek cihazda (Tab A9+, Mi 10T Pro) sarma hissi ve 4K/360° davranışı; performans modunun gerçek etkisi (küçük beklenir); tam emülatör turu, "360" kontrol noktası bitmedi.
- Not: Tab A9+ 4K 360 yüksek bitrate/HEVC videoda donanım çözücü sınırı büyük ihtimalle aşılıyor; yazılım çözücüyle de akıcı olmaz. Kullanıcıdan hata mesajı/VLC karşılaştırması bekleniyor.
- Sıradaki: kullanıcıdan Tab A9+ geri bildirimi (hata mesajı, aynı videonun VLC'de durumu).

## beta22 (2026-11-07)
İstek: Tab A9+ (SD695) üzerinde 4K HEVC 10-bit (Spider-Man, 3840x1600, 23,976 fps, ~6,4 Mbit/s) takılıyor; MX Player daha az takılarak oynatıyor. Hedef: ağır dosyalarda yazılımla en iyi verim.
Bulgu (nextlib 1.10.1-0.13.0 kaynağı okundu): FFmpeg thread sayısı zaten `availableProcessors()`. Darboğaz render yolu: her karede `ANativeWindow_lock` + `sws_scale` (10-bit → 8-bit YUV420P, SWS_BILINEAR) TEK iş parçacığında, oynatma iş parçacığını bloke ediyor. Kaynak: `ffvideo.cpp ffmpegRenderFrame`. Uygulama tarafından değiştirilemez; native fork gerekir.
- `PlaybackStats.kt` (yeni): AnalyticsListener ile çözücü adı ("ffmpeg<sürüm>-<kodek>") + video biçimi; `report()` hata raporuna "Son oynatma" bölümü yazar (biçim, çözücü, çizilen/atlanan kare).
- `SmoothnessWatchdog`: yalnız yerel dosya + FFmpeg çözücü + 1x hız + oynuyor iken saniyede örnek; 6 sn pencerede atlanan oran >%25 ya da kare hızı <%70 (fps biliniyorsa), art arda 3 kez kötüyse "akıcı değil".
- `PlayerActivity.checkSmoothness`: akıcı değilse (ayar `smoothSwitch`, varsayılan açık, Ayarlar → Video) `openInVlc(fromError=false)`; motor "yalnız yerleşik" ise yapmaz. Öğe başına bir kez.
- Metinler 12 dilde: `smooth_title`, `smooth_desc`, `playeracti_smooth_switch`.
- **Doğrulandı:** CI derleme + birim testleri + lint; kısa emülatör [ray] (14 adım, çökme 0, oynatıcı döngüsü çalıştı). [ayar] koşusu Altyazı→Liste→Tema sayfalarında çökmesiz ilerledi ama "Tema ve görünüm"de takılıp iptal edildi (bilinen sorun); Video sayfasındaki yeni anahtar emülatörde gezilmedi.
- **Doğrulanmadı:** gerçek cihazda (Tab A9+) bekçinin eşikleri, VLC'ye geçişin gerçekten daha akıcı olması (VLC'nin bu dosyada ne yaptığı bilinmiyor), kare atlama oranı. Çözücü adının cihazda gerçekten "ffmpeg…" ile başladığı (kaynaktan doğrulandı, cihazda görülmedi).
- Sıradaki (veriye bağlı): kullanıcıdan Tab A9+ hata raporu ("Son oynatma" bölümü). Veri VLC'nin de yetmediğini gösterirse: (a) `VideoDecoderGLSurfaceView` ile YUV çıkış modu (dönüşüm oynatma iş parçacığından çıkar), (b) nextlib native fork (çok iş parçacıklı dönüşüm / loop filter atlama; CI'da FFmpeg derlemesi gerekir, ağır iş).

## Yarım / sıradaki
- Kullanıcının kararı: önce uygulama özellik açısından tam ve hatasız olacak
- Sıradaki: kullanıcıdan beta20 geri bildirimi (altyazı/şerit sürükleme hissi); eski beta17 notları beta17–20 ile karşılandı
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
