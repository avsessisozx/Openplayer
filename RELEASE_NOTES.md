# 1.0.0-beta22

Ağır 4K videolarda yazılım çözücü takılmasına karşı ölçüm ve otomatik geçiş eklendi. Birim testleri ve derleme CI'da geçti; gerçek cihazda (Galaxy Tab A9+) his sende.

- **Akıcı değilse VLC'ye geç** (Ayarlar → Video, varsayılan açık): yerel bir videoda yazılım (FFmpeg) çözücüsüyle kareler birkaç saniye boyunca sürekli atlanıyorsa, oynatma aynı konumdan VLC motorunda devam eder. Oynatma motoru "yalnız yerleşik" seçiliyse devreye girmez.
- **Hata raporuna "Son oynatma" bölümü:** video biçimi (çözünürlük, kodek, bit derinliği, kare hızı, bit hızı), çalışan çözücü, çizilen/atlanan kare sayıları. Takılmayı ekran görüntüsü yerine rapordan teşhis edebilmek için.
