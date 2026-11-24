#!/usr/bin/env bash
set -eu
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 1080x2160
adb shell wm density 420
adb shell cmd uimode night yes
adb shell settings put system font_scale 1.0
adb shell input keyevent 82
mkdir -p screenshots
for screen in menu streaks; do
  adb shell am force-stop dev.ttmlonger.app
  adb shell am start -W -n dev.ttmlonger.app/dev.ttmlonger.PreviewActivity --es screen "$screen"
  expected="Поиск по настройкам мода"
  if [ "$screen" = streaks ]; then expected="Когда отправлять"; fi
  for attempt in $(seq 1 12); do
    adb shell uiautomator dump /sdcard/window.xml >/dev/null
    adb pull /sdcard/window.xml /tmp/window.xml >/dev/null
    if grep -Fq "$expected" /tmp/window.xml; then break; fi
    sleep 1
  done
  if ! grep -Fq "$expected" /tmp/window.xml; then
    cat /tmp/window.xml
    adb logcat -b crash -d
    exit 1
  fi
  adb exec-out screencap -p > "screenshots/$screen.png"
done
