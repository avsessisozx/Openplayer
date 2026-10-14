#!/usr/bin/env bash
# Emülatör testi için küçük örnek medya dosyaları üretir.
set -euo pipefail
out="${1:-media}"
mkdir -p "$out/Movies" "$out/Music"
ff() { ffmpeg -hide_banner -loglevel error -y "$@"; }

# Yatay H.264 + AAC
ff -f lavfi -i testsrc2=size=1280x720:rate=30:duration=20 -f lavfi -i sine=frequency=440:duration=20 \
   -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$out/Movies/Yatay video.mp4"

# Dikey video
ff -f lavfi -i testsrc=size=720x1280:rate=30:duration=10 -f lavfi -i sine=frequency=660:duration=10 \
   -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$out/Movies/Dikey video.mp4"

# MKV: bölümler + gömülü altyazı + dış altyazı
cat > /tmp/chapters.txt <<'TXT'
;FFMETADATA1
title=Bolum testi
[CHAPTER]
TIMEBASE=1/1000
START=0
END=8000
title=Giris
[CHAPTER]
TIMEBASE=1/1000
START=8000
END=16000
title=Orta
[CHAPTER]
TIMEBASE=1/1000
START=16000
END=24000
title=Son
TXT
cat > "$out/Movies/Bolumlu.srt" <<'SRT'
1
00:00:01,000 --> 00:00:04,000
Merhaba, bu bir altyazı testidir. ğüşıöç

2
00:00:05,000 --> 00:00:09,000
İkinci satır
<i>italik</i> yazı

3
00:00:10,000 --> 00:00:14,000
/Eğik çizgiyle başlayan satır

SRT
ff -f lavfi -i testsrc2=size=1920x1080:rate=25:duration=24 -f lavfi -i sine=frequency=330:duration=24 \
   -i "$out/Movies/Bolumlu.srt" -i /tmp/chapters.txt -map 0:v -map 1:a -map 2:s -map_metadata 3 -map_chapters 3 \
   -c:v libx264 -pix_fmt yuv420p -c:a aac -c:s srt -shortest "$out/Movies/Bolumlu.mkv"

cp "$out/Movies/Bolumlu.srt" "$out/Movies/Yatay video.srt"

# ExoPlayer'ın açamayacağı bir biçim (VLC yedek motoru testi)
ff -f lavfi -i testsrc=size=640x360:rate=25:duration=10 -f lavfi -i sine=frequency=550:duration=10 \
   -c:v wmv2 -c:a wmav2 -shortest "$out/Movies/Eski bicim.wmv"

# HEVC 10-bit (donanım çözücü zinciri testi)
ff -f lavfi -i testsrc2=size=1280x720:rate=30:duration=8 -c:v libx265 -pix_fmt yuv420p10le -tag:v hvc1 \
   -x265-params log-level=error "$out/Movies/HEVC 10bit.mp4"

# Bozuk dosya (hata yönetimi testi)
head -c 200000 /dev/urandom > "$out/Movies/Bozuk dosya.mp4"

# Müzik: etiketli MP3'ler, kapaklı olan, FLAC, LRC
ff -f lavfi -i "color=c=orange:s=500x500:d=1" -frames:v 1 /tmp/cover.png
i=0
for t in "Birinci Şarkı" "İkinci Şarkı" "Üçüncü Şarkı"; do
  i=$((i+1))
  ff -f lavfi -i "sine=frequency=$((300+i*100)):duration=30" -i /tmp/cover.png -map 0:a -map 1:v \
     -c:a libmp3lame -b:a 128k -c:v png -disposition:v attached_pic -id3v2_version 3 \
     -metadata title="$t" -metadata artist="Test Sanatçı" -metadata album="Test Albüm" \
     -metadata genre="Rock" -metadata date="2024" -metadata track="$i" "$out/Music/0$i - $t.mp3"
done
ff -f lavfi -i "sine=frequency=880:duration=25" -c:a flac \
   -metadata title="Kayıpsız Parça" -metadata artist="Başka Sanatçı" -metadata album="FLAC Albüm" \
   "$out/Music/Kayipsiz.flac"
ff -f lavfi -i "sine=frequency=220:duration=20" -c:a libopus "$out/Music/Etiketsiz.opus"
cat > "$out/Music/01 - Birinci Şarkı.lrc" <<'LRC'
[00:01.00]Birinci satır
[00:05.00]İkinci satır
[00:10.00]Üçüncü satır
LRC
cat > "$out/Music/Liste.m3u" <<'M3U'
#EXTM3U
01 - Birinci Şarkı.mp3
Kayipsiz.flac
olmayan-dosya.mp3
M3U
ls -la "$out"/*
