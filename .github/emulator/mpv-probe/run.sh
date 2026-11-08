#!/bin/bash
# Runs on the emulator host. Writes one <case>.txt, screenshots and logcat per case into out/.
set -u
P=org.openplayer.mpvprobe
mkdir -p out
adb install -r "$(ls .github/emulator/mpv-probe/build/outputs/apk/debug/*.apk | head -1)"
{
  adb shell getprop ro.product.cpu.abi
  adb shell getprop ro.hardware.egl
  adb shell dumpsys SurfaceFlinger | grep -iE "GLES|EGL" | head -5
} > out/device.txt
adb shell run-as $P mkdir -p files
for f in media/*; do
  adb push "$f" /data/local/tmp/ >/dev/null
  adb shell run-as $P cp "/data/local/tmp/$(basename "$f")" files/
done
D=/data/data/$P/files
while read -r name vo ctx hwdec target file <&3; do
  [ -z "${name:-}" ] && continue
  case "$file" in http*) ;; *) file=$D/$file ;; esac
  echo "=== $name"
  adb logcat -c
  adb shell am start -W -n $P/.ProbeActivity --es name "$name" --es vo "$vo" --es ctx "$ctx" \
    --es hwdec "$hwdec" --es target "$target" --es file "$file" >/dev/null
  sleep 6
  adb exec-out screencap -p > "out/$name.png"
  sleep 7
  adb exec-out screencap -p > "out/$name-2.png"
  adb logcat -d > "out/$name.logcat.txt"
  grep MPVPROBE "out/$name.logcat.txt" | sed 's/^.*MPVPROBE: //' > "out/$name.txt"
  grep -E "FATAL|signal 1[01]|DEBUG   :|dlopen failed" "out/$name.logcat.txt" | head -30 >> "out/$name.txt"
  adb shell am force-stop $P
  sleep 1
done 3< .github/emulator/mpv-probe/cases.txt
exit 0
