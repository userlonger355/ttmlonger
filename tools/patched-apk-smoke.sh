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
media="${TTM_MEDIA_DIR:-${RUNNER_TEMP:-/tmp}/ttmlonger-media}"
mkdir -p "$media"
record_pid=""
capture() {
  local name="$1"
  if ! adb exec-out screencap -p > "$media/$name.png" || [[ ! -s "$media/$name.png" ]]; then
    rm -f "$media/$name.png"
    echo "::warning::Screenshot $name unavailable"
  fi
}
finish_capture() {
  local rc=$?
  trap - EXIT
  set +e
  capture final
  if [[ -n "$record_pid" ]]; then
    adb shell 'pkill -INT screenrecord' >/dev/null 2>&1 || true
    wait "$record_pid" || true
    adb pull /sdcard/ttmlonger-startup.mp4 "$media/startup.mp4" >/dev/null 2>&1 || true
    adb shell rm -f /sdcard/ttmlonger-startup.mp4
  fi
  [[ -s "$media/startup.mp4" ]] || { rm -f "$media/startup.mp4"; echo "::warning::Video capture unavailable"; }
  adb shell am force-stop "$package"
  adb uninstall "$package" >/dev/null
  exit "$rc"
}
trap finish_capture EXIT
capture before-launch
adb shell screenrecord --time-limit 35 --bit-rate 4000000 /sdcard/ttmlonger-startup.mp4 > "$media/screenrecord.txt" 2>&1 &
record_pid=$!
adb shell am start -W -n "$component"
capture launch
sleep 5
capture after-5s
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
