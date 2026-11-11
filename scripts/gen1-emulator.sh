#!/usr/bin/env bash
# Create and run an Android 7.0 emulator shaped like the Peloton Bike gen 1 tablet.
# See docs/DEVICE.md for the spec and its sources.
#
#   scripts/gen1-emulator.sh            create if needed, boot headless, wait, apply display settings
#   scripts/gen1-emulator.sh --window   same, with a window
#   scripts/gen1-emulator.sh --create   create and configure the AVD only, do not boot
#   scripts/gen1-emulator.sh --stop     shut it down
#   scripts/gen1-emulator.sh --sync-music   copy your music to an emulator that is already running, then exit
#   scripts/gen1-emulator.sh --no-music     boot without copying music (combines with --window)
#
# Music: if SOLOTON_MUSIC_DIR names a folder on this computer (one sub-folder per game), its audio
# files (mp3, ogg, opus, m4a, aac, wav, flac) are copied to the emulator after boot, keeping the
# sub-folders and skipping files that are already there with the same size. The destination is
# SOLOTON_MUSIC_DEVICE_DIR, default /sdcard/Music/soloton, the app's default music root. Set both in
# a git-ignored .env file at the repo root (copy .env.example), or in the environment, which wins.
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
. "$ROOT/scripts/lib/dotenv.sh"
. "$ROOT/scripts/lib/music-sync.sh"
soloton_toolchain_env "$ROOT"
soloton_load_dotenv "$ROOT/.env"

mode=boot
music=1
window=(-no-window)
for arg in "$@"; do
  case "$arg" in
    --window)     window=() ;;
    --create)     mode=create ;;
    --stop)       mode=stop ;;
    --sync-music) mode=sync ;;
    --no-music)   music=0 ;;
    -h|--help)    sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done
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

if [ "$mode" = stop ]; then
  "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
  exit 0
fi

# Copies SOLOTON_MUSIC_DIR to the emulator. With "required", an unset or missing folder is an error.
sync_music() {
  local required="${1:-}" src="${SOLOTON_MUSIC_DIR:-}" dest="${SOLOTON_MUSIC_DEVICE_DIR:-/sdcard/Music/soloton}"
  if [ -z "$src" ]; then
    echo "No music copied: to use your own, set SOLOTON_MUSIC_DIR in .env (see .env.example)"
    [ -z "$required" ] || return 1
    return 0
  fi
  if [ ! -d "$src" ]; then
    echo "No music copied: SOLOTON_MUSIC_DIR is not a folder on this computer (check .env)" >&2
    [ -z "$required" ] || return 1
    return 0
  fi
  soloton_music_sync "$ADB" "$SERIAL" "$src" "$dest"
}

if [ "$mode" = sync ]; then
  if ! "$ADB" devices | grep -q "^$SERIAL[[:space:]]*device"; then
    echo "$SERIAL is not running. Start it with scripts/gen1-emulator.sh" >&2
    exit 1
  fi
  sync_music required
  exit $?
fi

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

if [ "$mode" = create ]; then
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
if [ "$music" = 1 ]; then
  # External storage comes up a little after boot_completed.
  for _ in $(seq 1 30); do "$ADB" -s "$SERIAL" shell 'ls /sdcard/' >/dev/null 2>&1 && break; sleep 1; done
  sync_music || echo "Music was not copied; the emulator is still usable." >&2
fi
echo "Ready: $SERIAL ($AVD, API 24, 1920x1080, ${DENSITY} dpi, 2 GB, 4 cores)"
