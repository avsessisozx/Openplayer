<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>免费、开源、无广告的 Android 视频与音乐播放器。<br>手势操作、HW / HW+ / SW 解码器、字幕搜索与翻译、360° 视频、SMB / FTP / WebDAV 网络播放。</b>
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
  <b>简体中文</b> ·
  <a href="README.ja.md">日本語</a> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## 简介

OpenPlayer 是一款开源的 Android 视频播放器，没有广告，也不追踪用户。它以自由软件的形式提供主流闭源播放器的实用功能：屏幕手势、字幕管理、解码器选择、网络来源等。所有代码均从零编写。

应用提供 12 种语言。翻译为机器翻译，未经母语者审校，欢迎提出修正。

## 下载

从[发布页面](https://github.com/avsessisozx/Openplayer/releases)下载 `OpenPlayer.apk` 并安装（Android 7.0+）。可能需要允许安装未知来源的应用。

> **状态：测试版。** 已在 CI 和模拟器上构建并测试，但尚未在真机上充分测试。请在 [Issues](https://github.com/avsessisozx/Openplayer/issues) 中反馈问题。

## 功能

- **解码器：** HW、HW+（FFmpeg 扩展：AC3、E-AC3、DTS、TrueHD、FLAC、Opus 等）和 SW，播放时可切换。ExoPlayer 无法打开的格式由 libVLC 备用引擎处理。
- **播放：** 0.25x–4x 倍速、长按 2x、A-B 循环、睡眠定时器、均衡器、最高 200% 音量增强、音频延迟、断点续播、画中画、后台播放、屏幕锁定与儿童锁、截图。
- **360° / VR：** 360° 视频与照片、运动传感器、VR 头显模式。
- **手势：** 左/中/右分区（亮度、音量、速度、快进快退、字幕、缩放），水平滑动快进，双击，双指缩放。
- **字幕：** SRT、WebVTT、ASS/SSA、SAMI、MicroDVD 等；延迟/速度调整、自动检测字符编码、样式与自定义字体；在 OpenSubtitles.com 搜索（需自备 [API 密钥](https://www.opensubtitles.com/consumers)）；**字幕翻译**（MyMemory、LibreTranslate、兼容 OpenAI 的 AI）。
- **文件：** 仅列出含视频的文件夹，网格/列表、排序、隐藏文件夹与私密（加锁）文件夹、书签、媒体管理器。
- **网络：** SMB、FTP、WebDAV；HTTP/HTTPS/RTSP/HLS/DASH 地址。
- **音乐播放器**（可选）：按歌曲、专辑、艺术家和文件夹浏览，播放队列，均衡器，标签编辑。

## 已知限制

不支持完整的 ASS/SSA（libass）；不显示图像字幕（VobSub、PGS）；没有 Chromecast 和去隔行；网络视频的外挂字幕不会自动查找。

## 构建

每次提交都由 **GitHub Actions** 构建。本地构建：JDK 17、Android SDK 和 `gradle assembleDebug`。

## 隐私与安全

无广告、无追踪、无统计分析。网络服务器和 OpenSubtitles 的密码以**未加密**形式保存在应用私有存储中。`app/openplayer-test.jks` 是公开的测试签名密钥。详情见 [SECURITY.md](../SECURITY.md)。

## 许可证

GNU AGPLv3（[LICENSE](../LICENSE)）。使用的组件：[Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1)。
