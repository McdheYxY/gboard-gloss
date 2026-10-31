#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "$0")/../.." && pwd)
sdk="$project_root/.android-sdk"
out="$project_root/app/build/device-test"
apk="$project_root/app/build/outputs/apk/release/app-release.apk"
test_log="$out/result.log"
adb=${ADB:-/mnt/d/shuaji/刷机/刷机/adb-fastboot/adb.exe}

app_package=io.github.ziyuw_plank.gboardgloss
gboard_package=com.google.android.inputmethod.latin
device_test_jar=/data/local/tmp/CandidatePopupProbe.jar
device_outputs=(
  /sdcard/gboard_candidates.png
  /sdcard/gboard_bubble.png
  /sdcard/gboard_windows_after_long.txt
  /sdcard/gboard_probe.xml
  /sdcard/gboard_probe.png
  /sdcard/gboard_windows.txt
)

if [[ ! -x "$adb" ]]; then
  echo "ADB not found: $adb" >&2
  exit 1
fi

if [[ -z "${SERIAL:-}" ]]; then
  serial=$("$adb" devices | tr -d '\r' | \
    awk 'NR > 1 && $2 == "device" { print $1; exit }')
else
  serial=$SERIAL
fi
if [[ -z "$serial" ]]; then
  echo "No authorized Android device found." >&2
  exit 1
fi

adb_path() {
  if [[ "$adb" == *.exe ]]; then
    wslpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

cleanup_device() {
  mkdir -p "$out/artifacts"
  for path in "${device_outputs[@]}"; do
    "$adb" -s "$serial" pull "$path" \
      "$(adb_path "$out/artifacts/$(basename "$path")")" >/dev/null 2>&1 || true
  done
  if [[ "${KEEP_DEVICE_TEST_ARTIFACTS:-0}" != "1" ]]; then
    "$adb" -s "$serial" shell "rm -f ${device_outputs[*]}" || true
  fi
  "$adb" -s "$serial" shell rm -f "$device_test_jar" || true
  "$adb" -s "$serial" shell am force-stop "$app_package" || true
  "$adb" -s "$serial" shell am force-stop "$gboard_package" || true
}
trap cleanup_device EXIT

echo "==> Building release APK"
"$project_root/gradlew" -p "$project_root" assembleRelease --no-daemon

echo "==> Installing $apk"
if ! "$adb" -s "$serial" install -r "$(adb_path "$apk")"; then
  echo "Install failed. If signatures differ, the existing app must be uninstalled manually." >&2
  exit 1
fi

echo "==> Restarting Gboard"
"$adb" -s "$serial" shell am force-stop "$gboard_package"

echo "==> Building device test"
rm -rf "$out/classes" "$out/dex"
mkdir -p "$out/classes" "$out/dex"
if [[ ! -f "$out/junit-3.8.1.jar" ]]; then
  curl -fL -o "$out/junit-3.8.1.jar" \
    https://maven.aliyun.com/repository/central/junit/junit/3.8.1/junit-3.8.1.jar
fi

javac -source 8 -target 8 \
  -cp "$sdk/platforms/android-34/android.jar:$sdk/platforms/android-34/uiautomator.jar:$out/junit-3.8.1.jar" \
  -d "$out/classes" \
  "$project_root/tools/device/android/test/RepetitiveTest.java" \
  "$project_root/tools/device/CandidatePopupProbe.java"

"$sdk/build-tools/34.0.0/d8" \
  --lib "$sdk/platforms/android-34/android.jar" \
  --output "$out/dex" \
  "$out/classes/device/CandidatePopupProbe.class" \
  "$out/classes/android/test/RepetitiveTest.class"

jar cf "$out/CandidatePopupProbe.jar" -C "$out/dex" classes.dex

echo "==> Running device test"
"$adb" -s "$serial" push "$(adb_path "$out/CandidatePopupProbe.jar")" "$device_test_jar"
"$adb" -s "$serial" shell am start -W -n "$app_package/.SettingsActivity"

if ! "$adb" -s "$serial" shell uiautomator runtest "$device_test_jar" \
    -c device.CandidatePopupProbe 2>&1 | tee "$test_log"; then
  echo "Device test command failed. Log: $test_log" >&2
  exit 1
fi
if ! grep -q "OK (1 test)" "$test_log"; then
  echo "Device test did not pass. Log: $test_log" >&2
  exit 1
fi

echo "==> Test passed"
