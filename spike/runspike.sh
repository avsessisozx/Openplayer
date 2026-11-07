#!/bin/bash
# kullanim: runspike.sh "<stages>" <azami-sn>
A="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe -s emulator-5580"; export MSYS_NO_PATHCONV=1
D=/sdcard/Android/data/org.openplayer.spike/files
$A shell am force-stop org.openplayer.spike; $A shell rm -f $D/result.txt; $A logcat -c
$A shell am start -n org.openplayer.spike/spike.SpikeActivity --es stages "$1" | tail -1 | cut -c1-80
end=$((SECONDS+$2))
while [ $SECONDS -lt $end ]; do sleep 3
  if $A shell cat $D/result.txt 2>/dev/null | grep -qE 'SPIKE\|DONE|SPIKE\|HANG'; then break; fi
  if ! $A shell pidof org.openplayer.spike >/dev/null; then echo "PROCESS DEAD"; break; fi
done
$A shell cat $D/result.txt | cut -c1-640
$A shell dumpsys activity exit-info org.openplayer.spike | grep -E 'reason=' | head -1 | cut -c1-110
$A logcat -d -b crash | head -14 | cut -c1-170
