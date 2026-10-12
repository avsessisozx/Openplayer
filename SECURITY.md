# Güvenlik Politikası

## Açık bildirme
Güvenlik açığı bulursanız lütfen herkese açık issue **açmayın**. Depodaki
**Security → Report a vulnerability** (özel güvenlik bildirimi) bölümünü kullanın.

## Tehdit modeli ve alınan önlemler
- **Dışarıdan gelen adresler**: Oynatıcı başka uygulamalardan gelen VIEW niyetlerini kabul eder. Şema beyaz listesi
  (content, file, http(s), rtsp, smb, ftp), uygulamanın kendi özel dosyalarını okumayı engelleme, öğe/uzunluk sınırı uygulanır.
- **Altyazı dosyaları (yerel/uzak)**: 5 MB sınır, en fazla 50.000 satır, satır başına 2.000 karakter; güvenli dosya adları;
  indirilen dosya sayısı sınırlı. Altyazılar yalnızca metin olarak işlenir (kod çalıştırılmaz).
- **OpenSubtitles**: İndirme bağlantısı yalnızca https, yönlendirme yok, boyut sınırlı.
- **Parolalar/API anahtarları**: Android Keystore (AES-GCM) ile şifrelenir; yedeğe (backup) dahil edilmez.
- **Kimlik bilgisi sızıntısı**: Kayıtlı sunucu bilgileri yalnızca host+port+şema birebir eşleşirse gönderilir; farklı sunucuya
  yönlendirmede Authorization başlığı düşürülür (OkHttp); https→http yönlendirme izlenmez.
- **Medya oturumu**: Yalnızca kendi uygulamamız ve sistem (güvenilir) denetleyiciler bağlanabilir.
- **CI/CD**: İş akışı varsayılan salt okunur; yazma yetkisi yalnızca release işinde. Bağımlılıklar Dependabot ile izlenir.

## Bilinen ödünleşimler
- `usesCleartextTraffic=true`: yerel ağdaki http/WebDAV/FTP akışları için gerekli; açık metin protokollerde ağdaki
  saldırgan trafiği görebilir. Mümkünse https/SMB kullanın; uygulama bu durumda uyarı gösterir.
- APK, herkese açık TEST anahtarıyla imzalıdır (güncellemelerin üst üste kurulması için). Mağaza dağıtımı için kendi anahtarınızı kullanın.
- Yerel medya çözücüleri (FFmpeg/NextLib) hatalı biçimli dosyalara karşı güncel tutulmalıdır.
