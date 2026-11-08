# mpv / FFmpeg licensing for OpenPlayer

Status: engineering review of the shipped binaries, not legal advice.
Checked 2026-11-07 against `io.github.abdallahmehiz:mpv-android-lib:0.1.12`
(file from Maven Central, wrapper tag `v0.1.12` = `96c3507c`).

## Result

- OpenPlayer is AGPL-3.0 (`LICENSE`). The AAR's native code is effectively **GPL-3.0-or-later**.
- These are compatible: section 13 of GPLv3 and section 13 of AGPLv3 explicitly allow linking
  or combining work under the two licenses. OpenPlayer's own code stays AGPLv3; the GPL
  components stay under GPLv3; AGPLv3 section 13 applies to the combination.
- The MIT label on the AAR's Maven page covers **only the wrapper** (`is.xyz.mpv.*`, JNI).
  It does not describe the bundled libraries.
- No `--enable-nonfree` in the FFmpeg configuration, so nothing makes the binary undistributable.
- Do not relicense OpenPlayer more permissively while this AAR is linked.

## Evidence from the binaries

Strings embedded in the `.so` files of the AAR, checked on arm64-v8a and x86_64 (identical):

- `libavutil.so`: `libavutil license: GPL version 3 or later`.
- FFmpeg configuration: `--enable-gpl --enable-version3 --enable-mbedtls --enable-libdav1d
  --enable-libxml2 --enable-jni --enable-mediacodec --disable-vulkan --disable-encoders
  --disable-devices --enable-encoder=mjpeg,png`.
- `libmpv.so` feature list includes `gpl` (mpv built with GPL-only files).

## Components

| Component | Version in the AAR | License | Source of version |
| --- | --- | --- | --- |
| mpv | v0.41.0-174-g76a5eba99 | GPL-2.0-or-later as built | binary |
| FFmpeg | N-122742-ga7522f3fef | GPL-3.0-or-later as configured | binary |
| libplacebo | v7.360.0 | LGPL-2.1-or-later | binary |
| libass | 0.17.4-21-gfadc39058 | ISC | binary |
| dav1d | 1.5.3-18-gdaef3962 | BSD-2-Clause | binary |
| mbedtls | 3.6.5 | Apache-2.0 | build script |
| libxml2 | 2.13.5 | MIT | build script |
| freetype | 2.14.1 | FTL or GPL-2.0-or-later | build script |
| fribidi | 1.0.16 | LGPL-2.1-or-later | build script |
| harfbuzz | 12.2.0 | MIT (Old MIT) | build script |
| libunibreak | 6.1 | Zlib | build script |
| Lua | 5.2.4 | MIT | build script |
| libc++ (NDK r29) | NDK | Apache-2.0 WITH LLVM-exception | build script |
| mpv-android wrapper | v0.1.12 | MIT | Maven POM, repository |
| `assets/subfont.ttf` (Droid Sans Fallback 2.56) | 2.56 | Apache-2.0 | font name table |
| `assets/cacert.pem` (Mozilla CA bundle) | 2025-12-02 | MPL-2.0 | file header |

"build script" means `buildscripts/include/depinfo.sh` at the wrapper tag; it was not
independently confirmed in the binary. The same file pins FFmpeg to `n8.0` for its CI, yet the
binary reports a newer git revision, so the binary string wins for FFmpeg.

The AAR does not bundle `rubberband`, so no rubberband license applies; it also
means `af=rubberband` is not available.

## What distribution requires

Each release that ships this AAR must:

1. Keep OpenPlayer's own source public (already true) and state its AGPLv3 license.
2. Provide the Corresponding Source of the GPL parts (GPLv3 section 6). **Done automatically:**
   every release gets `OpenPlayer-native-sources.tar` attached next to the APK (see below).
3. Show legal notices: list the components above with their licenses and include the GPLv3 text.
   An in-app screen or an asset file is enough; GPLv3 section 5(d) asks for appropriate legal
   notices in interactive programs. The Apache-2.0 components (mbedtls, font, libc++) need their
   license text; FreeType's FTL needs its credit line.
4. If the AAR version changes, repeat this check, update the table and `docs/native-sources.txt`.

## How the source is shipped

- `docs/native-sources.txt` lists every source archive with its exact commit or release:
  mpv, FFmpeg, libplacebo (with its git submodules), fribidi, libass, dav1d, freetype (with `dlg`),
  harfbuzz, libunibreak, mbedtls, libxml2, Lua and the wrapper's build scripts (`v0.1.12`).
- `.github/workflows/native-sources.yml` downloads them and packs them unchanged into
  `OpenPlayer-native-sources.tar` with `SHA256SUMS`, the list and this file.
- `build.yml` runs it before publishing. The release job needs it, so **if any download fails, no
  release is published** (no GPL binary without its source). It also fails when the AAR version in
  `app/build.gradle.kts` differs from the `aar` line in the list.
- Why not just link upstream: GPLv3 section 6(d) allows a third-party server only while we make sure
  the source stays available. A force-push, a deleted fork (the wrapper repository is a personal
  fork), or a moved tarball would leave our published APKs without source and us out of compliance.
  A copy attached to each release lives as long as the release does.
- Why not build the AAR ourselves: heavier (NDK, about an hour per ABI) and would change the binary
  we tested. Packing the exact sources is enough for the obligation.

Limits: tarball versions (mbedtls, freetype, harfbuzz, fribidi, libunibreak, libxml2, Lua) come
from the wrapper's build script and are not proven by strings in the binary. The wrapper's build
script would fetch the FFmpeg/mpv/libass/dav1d/libplacebo heads; we pin the commits reported by
the binaries instead.

## Sources

- FFmpeg license and legal notes: `LICENSE.md` in FFmpeg, https://ffmpeg.org/legal.html
- mpv: `Copyright` file in mpv, https://github.com/mpv-player/mpv
- Wrapper and build scripts: https://github.com/abdallahmehiz/mpv-android/tree/v0.1.12
- Maven POM: https://repo1.maven.org/maven2/io/github/abdallahmehiz/mpv-android-lib/0.1.12/
- GPLv3 sections 5, 6, 13: https://www.gnu.org/licenses/gpl-3.0.html
- AGPLv3 section 13: https://www.gnu.org/licenses/agpl-3.0.html
