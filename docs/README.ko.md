<p align="center">
  <img src="icon.png" width="120" alt="OpenPlayer">
</p>

<h1 align="center">OpenPlayer</h1>

<p align="center">
  <b>안드로이드용 무료 오픈 소스 광고 없는 동영상 및 음악 플레이어.<br>제스처 조작, HW / HW+ / SW 디코더, 자막 검색 및 번역, 360° 동영상, SMB / FTP / WebDAV 네트워크 재생.</b>
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
  <b>한국어</b> ·
  <a href="README.ru.md">Русский</a> ·
  <a href="README.ar.md">العربية</a>
</p>

---

## 소개

OpenPlayer는 광고와 추적이 없는 오픈 소스 안드로이드 동영상 플레이어입니다. 인기 있는 비공개 소스 플레이어의 유용한 기능(화면 제스처, 자막 관리, 디코더 선택, 네트워크 소스 등)을 자유 소프트웨어로 제공하는 것이 목표입니다. 모든 코드는 처음부터 새로 작성되었습니다.

앱은 12개 언어로 제공됩니다. 번역은 기계 번역이며 원어민의 검수를 거치지 않았습니다. 수정 제안을 환영합니다.

## 다운로드

[릴리스 페이지](https://github.com/avsessisozx/Openplayer/releases)에서 `OpenPlayer.apk`를 내려받아 설치하세요(Android 7.0 이상). 출처를 알 수 없는 앱 설치를 허용해야 할 수 있습니다.

> **릴리스 페이지에서만 다운로드하세요.** 서명 키는 공개되어 있습니다(오픈 소스). 다른 곳의 APK는 정식 앱 위에 설치되는 변조된 사본일 수 있습니다.

> **상태: 베타.** CI와 에뮬레이터에서 빌드 및 테스트했으나 실제 기기에서는 아직 충분히 테스트하지 않았습니다. 문제는 [Issues](https://github.com/avsessisozx/Openplayer/issues)에 알려 주세요.

## 기능

- **디코더:** HW, HW+(FFmpeg 확장: AC3, E-AC3, DTS, TrueHD, FLAC, Opus 등), SW. 재생 중 전환 가능. ExoPlayer가 열지 못하는 형식은 libVLC 대체 엔진으로 재생합니다.
- **재생:** 0.25x–4x 속도, 길게 눌러 2x, A-B 반복, 취침 타이머, 이퀄라이저, 최대 200% 볼륨 부스트, 오디오 지연, 이어서 재생, PiP, 백그라운드 재생, 화면 잠금 및 자녀 보호 잠금, 스크린샷.
- **360° / VR:** 360° 동영상 및 사진, 모션 센서, VR 헤드셋 모드.
- **제스처:** 왼쪽/가운데/오른쪽 영역(밝기, 볼륨, 속도, 탐색, 자막, 확대/축소), 가로 스와이프로 탐색, 두 번 탭, 핀치 확대/축소.
- **자막:** SRT, WebVTT, ASS/SSA, SAMI, MicroDVD 등. 지연/속도 조정, 문자 인코딩 자동 감지, 스타일 및 사용자 글꼴. OpenSubtitles.com 검색(직접 발급한 [API 키](https://www.opensubtitles.com/consumers) 필요). **자막 번역**(MyMemory, LibreTranslate, OpenAI 호환 AI).
- **파일:** 동영상이 있는 폴더만 표시, 격자/목록, 정렬, 숨김 폴더와 비공개(잠금) 폴더, 북마크, 미디어 관리자.
- **네트워크:** SMB, FTP, WebDAV, HTTP/HTTPS/RTSP/HLS/DASH 주소.
- **음악 플레이어**(선택): 노래, 앨범, 아티스트, 폴더별 보기, 대기열, 이퀄라이저, 태그 편집.

## 알려진 제한 사항

완전한 ASS/SSA(libass)는 지원하지 않습니다. 이미지 자막(VobSub, PGS)은 표시되지 않습니다. Chromecast와 인터레이스 해제가 없습니다. 네트워크 동영상의 외부 자막은 자동으로 찾지 않습니다.

## 빌드

모든 변경은 **GitHub Actions**에서 빌드됩니다. 로컬에서는 JDK 17, Android SDK, `gradle assembleDebug`를 사용합니다.

## 개인정보 및 보안

광고, 추적, 분석이 없습니다. 네트워크 서버와 OpenSubtitles 비밀번호는 앱의 비공개 저장소에 **암호화 없이** 저장됩니다. `app/openplayer-test.jks`는 공개된 테스트용 서명 키입니다. 자세한 내용은 [SECURITY.md](../SECURITY.md)를 참조하세요.

## 라이선스

GNU AGPLv3([LICENSE](../LICENSE)). 사용된 구성 요소: [Media3/ExoPlayer](https://github.com/androidx/media) (Apache-2.0), [NextLib](https://github.com/anilbeesetti/nextlib) (FFmpeg, GPLv3), [smbj](https://github.com/hierynomus/smbj), [Apache Commons Net](https://commons.apache.org/proper/commons-net/), [OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/) (Apache-2.0), [libVLC](https://code.videolan.org/videolan/vlc-android) (LGPL-2.1).
