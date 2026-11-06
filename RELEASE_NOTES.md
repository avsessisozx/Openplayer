# 1.0.0-beta21

Sarma ve ön bellek akıcılığı iyileştirildi, ağır 4K/360° videolar için performans modu eklendi. Birim testleri ve derleme geçti; gerçek cihazda (özellikle Galaxy Tab A9+) his sende.

- **Daha akıcı sarma:** oynatılmış kısmın son 15 sn'si bellekte tutulur, kısa geri sarmalar anında olur. Ön bellek eşikleri düşürüldü, sarma sonrası bekleme kısaldı.
- **Seri ±sn dokunuşları:** art arda hızlı dokunuşlarda en yakın anahtar kareye gidilir, seri bitince tam konum ayarı geri gelir. 4K videolarda parmakla sararken istek aralığı açıldı.
- **Performans modu** (Ayarlar → Video): oynarken işlemciyi yavaşlatmamasını ister. Pil daha çok gider, cihaz ısınabilir; donanımın sınırını aşmaz.
- **Açılmayan videoda net mesaj:** video çözünürlüğü ve biçimiyle birlikte "bu cihazın sınırının üstünde" uyarısı ve ne denenebileceği gösterilir.
