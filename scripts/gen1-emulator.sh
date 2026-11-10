#!/usr/bin/env bash
# Create and run an Android 7.0 emulator shaped like the Peloton Bike gen 1 tablet.
# See docs/DEVICE.md for the spec and its sources.
#
#   scripts/gen1-emulator.sh            create if needed, boot headless, wait, apply display settings
#   scripts/gen1-emulator.sh --window   same, with a window
#   scripts/gen1-emulator.sh --create   create and configure the AVD only, do not boot
#   scripts/gen1-emulator.sh --stop     shut it down
#
# Finds the SDK and JDK itself (scripts/lib/toolchain.sh; scripts/doctor.sh --fix installs them).
# Installs the emulator and the
# API 24 system image (x86_64, or arm64-v8a on ARM hosts) with sdkmanager if they are missing.
set -euo pipefail

AVD=soloton_gen1
PORT=5590
SERIAL=emulator-$PORT
DENSITY=160          # assumed; see docs/DEVICE.md
case "$(uname -m)" in
  arm64|aarch64) ABI=arm64-v8a ;;
  *)             ABI=x86_64 ;;
esac
IMAGE="system-images;android-24;default;$ABI"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/scripts/lib/toolchain.sh"
soloton_toolchain_env "$ROOT"
if [ -z "${ANDROID_HOME:-}" ] || [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" ]; then
  echo "No Android SDK with command-line tools found. Run scripts/doctor.sh --fix" >&2
  exit 1
fi
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null 2>&1; then
  echo "No JDK found (avdmanager needs one). Run scripts/doctor.sh --fix" >&2
  exit 1
fi
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDM="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
EMU="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"

if [ "${1:-}" = "--stop" ]; then
  "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
  exit 0
fi

window=(-no-window)
[ "${1:-}" = "--window" ] && window=()

if [ ! -x "$EMU" ] || [ ! -d "$ANDROID_HOME/system-images/android-24/default/$ABI" ]; then
  echo "Installing emulator and $IMAGE"
  "$SDKM" --install emulator "$IMAGE" >/dev/null
fi

if [ ! -d "$AVD_HOME/$AVD.avd" ]; then
  echo "Creating AVD $AVD"
  echo no | "$AVDM" create avd --force --name "$AVD" --package "$IMAGE" --device "pixel_c" >/dev/null
fi

# Shape it like the bike tablet: 1920x1080 landscape, 160 dpi, 2 GB RAM, 4 cores, no keyboard or camera.
cfg="$AVD_HOME/$AVD.avd/config.ini"
set_cfg() { if grep -q "^$1[ ]*=" "$cfg"; then sed -i "s|^$1[ ]*=.*|$1=$2|" "$cfg"; else echo "$1=$2" >> "$cfg"; fi; }
set_cfg hw.lcd.width 1920
set_cfg hw.lcd.height 1080
set_cfg hw.lcd.density "$DENSITY"
set_cfg hw.ramSize 2048M
set_cfg vm.heapSize 256M
set_cfg hw.cpu.ncore 4
set_cfg hw.keyboard no
set_cfg hw.camera.back none
set_cfg hw.camera.front none
set_cfg hw.initialOrientation landscape
set_cfg skin.name 1920x1080
set_cfg skin.path _no_skin
set_cfg showDeviceFrame no

if [ "${1:-}" = "--create" ]; then
  echo "AVD $AVD ready (not started)"
  exit 0
fi

# Is our emulator already up, and was it started without a window?
running_headless() { ps -eo args 2>/dev/null | grep -v grep | grep -- "-avd $AVD " | grep -q -- "-no-window"; }

if "$ADB" devices | grep -q "^$SERIAL" && [ "${#window[@]}" -eq 0 ] && running_headless; then
  echo "$SERIAL is running without a window; restarting it with one"
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do "$ADB" devices | grep -q "^$SERIAL" || break; sleep 1; done
fi

if "$ADB" devices | grep -q "^$SERIAL"; then
  if running_headless; then echo "$SERIAL already running (headless; use --window to show it)"
  else echo "$SERIAL already running"; fi
else
  "$EMU" -avd "$AVD" -port "$PORT" "${window[@]}" -gpu swiftshader_indirect \
    -no-snapshot -no-boot-anim -noaudio -memory 2048 -cores 4 >/dev/null 2>&1 &
fi

"$ADB" -s "$SERIAL" wait-for-device
for _ in $(seq 1 120); do
  [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 2
done
[ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r')" = "1" ] || { echo "boot timed out"; exit 1; }

"$ADB" -s "$SERIAL" shell wm size 1920x1080
"$ADB" -s "$SERIAL" shell wm density "$DENSITY"
"$ADB" -s "$SERIAL" shell settings put system accelerometer_rotation 0
"$ADB" -s "$SERIAL" shell settings put system user_rotation 1
"$ADB" -s "$SERIAL" shell settings put secure immersive_mode_confirmations confirmed
for s in window_animation_scale transition_animation_scale animator_duration_scale; do
  "$ADB" -s "$SERIAL" shell settings put global "$s" 0
done
echo "Ready: $SERIAL ($AVD, API 24, 1920x1080, ${DENSITY} dpi, 2 GB, 4 cores)"
