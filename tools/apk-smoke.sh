#!/usr/bin/env bash
set -euo pipefail
apk="${1:?TikTok APK path required}"
aapt="$(find "$ANDROID_HOME/build-tools" -type f -name aapt | sort -V | tail -n 1)"
badging="$("$aapt" dump badging "$apk")"
package="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<< "$badging" | head -n 1)"
case "$package" in
  com.zhiliaoapp.musically|com.ss.android.ugc.trill) ;;
  *) echo "::error::Unexpected TikTok package: $package"; exit 1 ;;
esac
echo "Emulator ABIs: $(adb shell getprop ro.product.cpu.abilist)"
echo "APK ABIs: $(grep '^native-code:' <<< "$badging" || true)"
adb wait-for-device
adb logcat -c
adb install --no-streaming "$apk"
component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -n 1)"
if [[ "$component" != "$package/"* ]]; then
  echo "::error::No launcher activity found"
  exit 1
fi
adb shell am start -W -n "$component"
sleep 15
if ! adb shell pidof "$package" >/dev/null; then
  echo "::error::TikTok process did not survive startup"
  adb logcat -b crash -d -t 200
  exit 1
fi
echo "### Unmodified TikTok startup passed (no login, no mod)" >> "$GITHUB_STEP_SUMMARY"
echo "TikTok process survived the startup smoke test."
adb shell am force-stop "$package"
adb uninstall "$package"
