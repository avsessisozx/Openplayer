<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>無料・オープンソース・広告なしの Android 用動画／音楽プレーヤー。<br>ジェスチャー操作、HW / HW+ / SW デコーダー、字幕の検索と翻訳、360° 動画、SMB / FTP / WebDAV ネットワーク再生。</b>
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
  <b>日本語</b> ·
  <a href="README.ko.md">한국어</a> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## 概要

OpenPlayer は、広告もトラッキングもないオープンソースの Android 動画プレーヤーです。人気のクローズドソース製プレーヤーの便利な機能（画面ジェスチャー、字幕管理、デコーダー選択、ネットワークソースなど）を自由なソフトウェアとして提供します。すべてゼロから書かれています。

アプリは 12 言語に対応しています。翻訳は機械翻訳で、ネイティブスピーカーによる確認は行われていません。修正のご提案を歓迎します。

## ダウンロード

[リリースページ](https://github.com/avsessisozx/Openplayer/releases)から `OpenPlayer.apk` をダウンロードしてインストールしてください（Android 7.0 以上）。提供元不明のアプリのインストールを許可する必要がある場合があります。

> **ダウンロードはリリースページからのみ行ってください。** 署名キーは公開されています(オープンソース)。他の場所の APK は、本物のアプリに上書きインストールされる改ざん版かもしれません。

> **ステータス：ベータ版。** CI とエミュレーターでビルド・テスト済みですが、実機での十分なテストはまだです。問題は [Issues](https://github.com/avsessisozx/Openplayer/issues) へご報告ください。

## 機能

- **再生エンジン：** libmpv（mpv + FFmpeg）。デコーダー：**HW**（ハードウェアのみ、MediaCodec）、**HW+**（ハードウェア。失敗時はコピー方式のハードウェア、次にソフトウェア）、**SW**（ソフトウェアのみ、FFmpeg）。再生中に切り替え可能。
- **新機能（beta23）：** ASS/SSA 字幕のフルスタイル表示（libass）、埋め込み字幕の翻訳（MKV/WebM/MP4）、音声のナイトモード。
- **再生：** 0.25x〜4x の速度、長押しで 2x、A-B リピート、スリープタイマー、イコライザー、最大 200% の音量ブースト、音声遅延、続きから再生、PiP、バックグラウンド再生、画面ロックとお子様ロック、スクリーンショット。
- **360° / VR：** 360° 動画・写真、モーションセンサー、VR ヘッドセットモード。
- **ジェスチャー：** 左／中央／右のゾーン（明るさ、音量、速度、シーク、字幕、ズーム）、水平スワイプでシーク、ダブルタップ、ピンチでズーム。
- **字幕：** SRT、WebVTT、ASS/SSA、SAMI、MicroDVD など。遅延／速度の調整、文字コードの自動判別、スタイルと独自フォント。OpenSubtitles.com で検索（ご自身の [API キー](https://www.opensubtitles.com/consumers)が必要）。**字幕翻訳**（MyMemory、LibreTranslate、OpenAI 互換 AI）。
- **ファイル：** 動画があるフォルダのみ表示、グリッド／リスト、並べ替え、非表示フォルダとプライベート（ロック）フォルダ、ブックマーク、メディアマネージャー。
- **ネットワーク：** SMB、FTP、WebDAV、HTTP/HTTPS/RTSP/HLS/DASH アドレス。
- **音楽プレーヤー**（任意）：曲・アルバム・アーティスト・フォルダ別の表示、キュー、イコライザー、タグ編集。

## 既知の制限

画像字幕（VobSub、PGS）は mpv で表示されますが、翻訳はできず、360° モードでは表示されません。Chromecast とインターレース解除はありません。ネットワーク動画の外部字幕は自動検出されません。

## ビルド

変更は毎回 **GitHub Actions** でビルドされます。ローカルでは JDK 17、Android SDK、`gradle assembleDebug` を使用します。

## プライバシーとセキュリティ

広告、トラッキング、解析はありません。ネットワークサーバーと OpenSubtitles のパスワードは、アプリの専用領域に**暗号化されずに**保存されます。`app/openplayer-test.jks` は公開のテスト用署名キーです。詳細は [SECURITY.md](../SECURITY.md) をご覧ください。

## ライセンス

GNU AGPLv3（[LICENSE](../LICENSE)）。再生エンジン：[mpv](https://mpv.io) (GPL-2.0+), [FFmpeg](https://ffmpeg.org) (GPL-3.0+), [libass](https://github.com/libass/libass) (ISC), [libplacebo](https://code.videolan.org/videolan/libplacebo) (LGPL-2.1+), [dav1d](https://code.videolan.org/videolan/dav1d) (BSD-2-Clause), FreeType, HarfBuzz, FriBidi, mbedTLS, libxml2, Lua — 同梱ライブラリ: [mpv-android-lib](https://github.com/abdallahmehiz/mpv-android) (MIT); [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) (Apache-2.0). 全一覧・ライセンス・ソースアーカイブ：[mpv-licenses.md](mpv-licenses.md)。
