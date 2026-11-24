#!/usr/bin/env bash
# Run only on a dedicated test device or clean native ARM64 Android emulator.
set -euo pipefail
apk="${1:?Usage: native-arm64-smoke.sh PATCHED_APK ADB_SERIAL}"
serial="${2:?Specify the dedicated test device serial}"
[[ -f "$apk" ]] || { echo "APK file not found"; exit 2; }
command -v adb >/dev/null
export ANDROID_SERIAL="$serial"
adb wait-for-device
primary="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
echo "Native device ABI: $primary"
[[ "$primary" == arm64-v8a ]] || { echo "Use a native ARM64 device, not x86 translation"; exit 2; }
bridge="$(adb shell getprop ro.dalvik.vm.native.bridge | tr -d '\r')"
if [[ -n "$bridge" && "$bridge" != 0 ]]; then
  echo "Native bridge active ($bridge); refusing ambiguous architecture test."
  exit 2
fi
package=com.zhiliaoapp.musically
if adb shell pm path "$package" | grep -q '^package:'; then
  echo "TikTok is already installed. Use a dedicated clean test device; no existing app will be removed."
  exit 2
fi
# The shared smoke test clears device logs and installs/uninstalls the test APK.
# Never run it against a personal device with an existing TikTok installation.
echo "Starting isolated ARM64 smoke test"
bash "$(dirname "$0")/patched-apk-smoke.sh" "$apk"
