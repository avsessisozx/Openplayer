<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>Бесплатный видео- и музыкальный плеер для Android с открытым исходным кодом и без рекламы.<br>Управление жестами, декодеры HW / HW+ / SW, поиск и перевод субтитров, видео 360°, воспроизведение по сети SMB / FTP / WebDAV.</b>
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Openplayer/releases"><img src="https://img.shields.io/github/v/release/avsessisozx/Openplayer?include_prereleases&label=download" alt="release"></a>
  <img src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 7.0+">
  <a href="../LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Openplayer" alt="AGPLv3"></a>
</p>

<p align="center">
  <a href="../README.md">English</a> ·
  <a href="README.tr.md">Türkçe</a> ·
  <a href="README.es.md">Español</a> ·
  <a href="README.fr.md">Français</a> ·
  <a href="README.it.md">Italiano</a> ·
  <a href="README.de.md">Deutsch</a> ·
  <a href="README.pt-BR.md">Português</a> ·
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <b>Русский</b> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## Что это

OpenPlayer — видеоплеер для Android с открытым исходным кодом, без рекламы и слежки. Он предлагает в виде свободного ПО полезные функции популярных проприетарных плееров: жесты на экране, управление субтитрами, выбор декодера, сетевые источники и другое. Весь код написан с нуля.

Приложение доступно на 12 языках. Переводы машинные и не проверены носителями языка; исправления приветствуются.

## Загрузка

Скачайте `OpenPlayer.apk` на [странице релизов](https://github.com/avsessisozx/Openplayer/releases) и установите (Android 7.0+). Возможно, потребуется разрешить установку из неизвестных источников.

> **Статус: бета.** Собрано и проверено на CI и эмуляторе, но пока не проверено широко на реальных устройствах. О проблемах сообщайте в [Issues](https://github.com/avsessisozx/Openplayer/issues).

## Возможности

- **Декодеры:** HW, HW+ (расширение FFmpeg: AC3, E-AC3, DTS, TrueHD, FLAC, Opus…) и SW, переключаются во время воспроизведения. Запасной движок libVLC для форматов, которые не открывает ExoPlayer.
- **Воспроизведение:** скорость 0,25x–4x, 2x по долгому нажатию, повтор A-B, таймер сна, эквалайзер, усиление громкости до 200 %, задержка звука, продолжение с места остановки, PiP, фоновое воспроизведение, блокировка экрана и детская блокировка, скриншоты.
- **360° / VR:** видео и фото 360°, датчик движения, режим VR-гарнитуры.
- **Жесты:** зоны слева/в центре/справа (яркость, громкость, скорость, перемотка, субтитры, масштаб), горизонтальный свайп для перемотки, двойное касание, масштабирование щипком.
- **Субтитры:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD и другие; настройка задержки/скорости, автоопределение кодировки, стиль и собственный шрифт; поиск на OpenSubtitles.com (нужен ваш собственный [ключ API](https://www.opensubtitles.com/consumers)); **перевод субтитров** (MyMemory, LibreTranslate, ИИ, совместимый с OpenAI).
- **Файлы:** только папки с видео, сетка/список, сортировка, скрытые и приватная (заблокированная) папки, закладки, медиаменеджер.
- **Сеть:** SMB, FTP, WebDAV; адреса HTTP/HTTPS/RTSP/HLS/DASH.
- **Музыкальный плеер** (по желанию): просмотр по песням, альбомам, исполнителям и папкам, очередь, эквалайзер, редактирование тегов.

## Известные ограничения

Нет полной поддержки ASS/SSA (libass); графические субтитры (VobSub, PGS) не отображаются; нет Chromecast и деинтерлейсинга; внешние субтитры для сетевых видео автоматически не ищутся.

## Сборка

Каждое изменение собирается через **GitHub Actions**. Локально: JDK 17, Android SDK и `gradle assembleDebug`.

## Конфиденциальность и безопасность

Нет рекламы, слежки и аналитики. Пароли сетевых серверов и OpenSubtitles хранятся в закрытом хранилище приложения **без шифрования**. `app/openplayer-test.jks` — публичный тестовый ключ подписи. Подробности: [SECURITY.md](../SECURITY.md).

## Лицензия

GNU AGPLv3 ([LICENSE](../LICENSE)). Используемые компоненты: [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
