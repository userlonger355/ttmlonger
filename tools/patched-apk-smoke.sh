#!/usr/bin/env bash
set -euo pipefail
apk="${1:?Patched APK path required}"
package=com.zhiliaoapp.musically
adb wait-for-device
echo "Emulator ABIs: $(adb shell getprop ro.product.cpu.abilist)"
adb logcat -c
adb install --no-streaming "$apk"
component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$package" | tr -d '\r' | tail -n 1)"
[[ "$component" == "$package/"* ]] || { echo "::error::No launcher activity"; exit 1; }
adb shell am start -W -n "$component"
for attempt in $(seq 1 12); do
  if adb logcat -d -s TTMLonger:I '*:S' | grep -q 'TTMLONGER_BOOT_READY'; then break; fi
  sleep 5
done
logs="$(adb logcat -d -s TTMLonger:I '*:S')"
printf '%s\n' "$logs" | grep -E 'TTMLONGER_(HOOK_ENTRY|BOOT_READY)' || true
if ! adb shell pidof "$package" >/dev/null; then
  echo "::error::Patched TikTok process did not survive startup"
  adb logcat -b crash -d -t 100
  exit 1
fi
if ! grep -q 'TTMLONGER_HOOK_ENTRY' <<< "$logs"; then
  echo "::error::TTMLonger entry marker missing"
  exit 1
fi
if ! grep -q 'TTMLONGER_BOOT_READY' <<< "$logs"; then
  echo "::error::TTMLonger initialization marker missing"
  exit 1
fi
echo "### Patched TikTok started and TTMLonger initialization reached its completion marker" >> "$GITHUB_STEP_SUMMARY"
echo "Individual feature hooks still need separate checks."
adb shell am force-stop "$package"
adb uninstall "$package"
