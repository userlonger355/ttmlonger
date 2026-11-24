#!/usr/bin/env bash
set -eu
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell wm size 1080x2160
adb shell wm density 420
adb shell settings put system font_scale 1.0
adb shell input keyevent 82
mkdir -p screenshots
for theme in dark light; do
  night=yes
  [ "$theme" = dark ] || night=no
  adb shell cmd uimode night "$night"
  for screen in menu streaks chat glass; do
    adb shell am force-stop dev.ttmlonger.app
    adb logcat -b crash -c
    adb shell am start -W -n dev.ttmlonger.app/dev.ttmlonger.PreviewActivity --es screen "$screen"
    case "$screen" in
      menu) expected="Поиск по настройкам мода";;
      streaks) expected="Когда отправлять";;
      chat) expected="Предпросмотр чата";;
      glass) expected="Нижняя панель";;
    esac
    found=0
    for attempt in $(seq 1 12); do
      adb shell uiautomator dump /sdcard/window.xml >/dev/null
      adb pull /sdcard/window.xml /tmp/window.xml >/dev/null
      if grep -Fq "$expected" /tmp/window.xml; then found=1; break; fi
      if adb logcat -b crash -d | grep -Fq "FATAL EXCEPTION"; then break; fi
      sleep 1
    done
    if [ "$found" != 1 ]; then
      cat /tmp/window.xml
      adb logcat -b crash -d
      exit 1
    fi
    adb exec-out screencap -p > "screenshots/${theme}-${screen}.png"
    echo "PREVIEW $theme/$screen PASS"
  done
done

python3 tools/ui-preview/home-gesture.py
