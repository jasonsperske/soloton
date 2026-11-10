#!/usr/bin/env bash
# Check everything Soloton needs to build, test and publish, and optionally fix what can be fixed.
#
#   scripts/doctor.sh          report only; changes nothing
#   scripts/doctor.sh --fix    also apply automatic fixes
#   --no-emulator              skip the optional gen 1 emulator checks (and its ~1 GB download)
#   --shell                    with --fix: also load the toolchain in every new shell, by adding
#                              one line to ~/.bashrc, ~/.zshrc or ~/.profile (opt-in: it sets
#                              JAVA_HOME for everything you run, not just Soloton)
#
# Automatic fixes install into a private toolchain directory, never system-wide:
#   ${SOLOTON_TOOLCHAIN:-~/.local/share/soloton-toolchain}
# They download a Temurin JDK 17 (Adoptium) and the Android command-line tools (Google),
# verify both against the checksums their publishers list, install SDK packages with
# sdkmanager (accepting the Android SDK licences), write local.properties so Gradle finds
# the SDK, and create the gen 1 tablet emulator. Anything that needs root (KVM access,
# Xvfb, Mesa) or an account (GitHub login) is reported with the command to run yourself.
#
# Exit status: 0 when every required check passes, 1 otherwise. Optional checks only warn.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLCHAIN="${SOLOTON_TOOLCHAIN:-$HOME/.local/share/soloton-toolchain}"
AVD_NAME=soloton_gen1
AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.android/avd}"
MIN_JAVA=17

FIX=0; EMULATOR=1; SHELLRC=0
for arg in "$@"; do
  case "$arg" in
    --fix) FIX=1 ;;
    --no-emulator) EMULATOR=0 ;;
    --shell) SHELLRC=1 ;;
    -h|--help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $arg (try --help)" >&2; exit 2 ;;
  esac
done

# ---------- output ----------
if [ -t 1 ]; then
  C_OK=$'\e[32m'; C_WARN=$'\e[33m'; C_FAIL=$'\e[31m'; C_DIM=$'\e[2m'; C_BOLD=$'\e[1m'; C_OFF=$'\e[0m'
else
  C_OK=; C_WARN=; C_FAIL=; C_DIM=; C_BOLD=; C_OFF=
fi
FAILS=0; WARNS=0; FIXED=0
section() { printf '\n%s%s%s\n' "$C_BOLD" "$1" "$C_OFF"; }
ok()      { printf '  %s✓%s %-28s %s\n' "$C_OK" "$C_OFF" "$1" "$2"; }
warn()    { printf '  %s!%s %-28s %s\n' "$C_WARN" "$C_OFF" "$1" "$2"; WARNS=$((WARNS + 1)); }
fail()    { printf '  %s✗%s %-28s %s\n' "$C_FAIL" "$C_OFF" "$1" "$2"; FAILS=$((FAILS + 1)); }
hint()    { printf '    %s→ %s%s\n' "$C_DIM" "$1" "$C_OFF"; }
fixing()  { printf '    %s… %s%s\n' "$C_DIM" "$1" "$C_OFF"; }
fixed()   { FIXED=$((FIXED + 1)); }
autofix() { [ "$FIX" = 1 ] || hint "fix automatically: scripts/doctor.sh --fix"; }

have() { command -v "$1" >/dev/null 2>&1; }

# ---------- platform ----------
OS="$(uname -s)"; ARCH="$(uname -m)"
case "$OS" in Linux) ADOPT_OS=linux; SDK_OS=linux ;; Darwin) ADOPT_OS=mac; SDK_OS=macosx ;; *) ADOPT_OS=; SDK_OS= ;; esac
case "$ARCH" in x86_64|amd64) ADOPT_ARCH=x64; IMG_ABI=x86_64 ;; arm64|aarch64) ADOPT_ARCH=aarch64; IMG_ABI=arm64-v8a ;; *) ADOPT_ARCH=; IMG_ABI= ;; esac
CAN_DOWNLOAD=0; [ -n "$ADOPT_OS" ] && [ -n "$ADOPT_ARCH" ] && CAN_DOWNLOAD=1

# ---------- requirements read from the repo ----------
toml_version() { sed -n "s/^$1 *= *\"\(.*\)\"/\1/p" "$ROOT/gradle/libs.versions.toml" | head -1; }
COMPILE_SDK="$(toml_version android-compileSdk)"; COMPILE_SDK="${COMPILE_SDK:-36}"
BUILD_TOOLS="${COMPILE_SDK}.0.0"     # AGP 9.3 uses build-tools matching compileSdk
GRADLE_VERSION="$(sed -n 's/.*gradle-\([0-9.]*\)-bin.zip/\1/p' "$ROOT/gradle/wrapper/gradle-wrapper.properties" 2>/dev/null)"
PKG_PLATFORM="platforms;android-$COMPILE_SDK"
PKG_BUILD_TOOLS="build-tools;$BUILD_TOOLS"
PKG_IMAGE="system-images;android-24;default;$IMG_ABI"

# ---------- helpers ----------
sha_of() { # algo file
  if [ "$1" = sha256 ]; then
    if have sha256sum; then sha256sum "$2" | cut -d' ' -f1; else shasum -a 256 "$2" | cut -d' ' -f1; fi
  else
    if have sha1sum; then sha1sum "$2" | cut -d' ' -f1; else shasum -a 1 "$2" | cut -d' ' -f1; fi
  fi
}

java_major() { # java binary -> major version, empty if it does not run
  "$1" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | sed 's/^1\.//' | head -1
}

jdk_home_in() { # toolchain jdk dir -> JAVA_HOME (mac tarballs nest it under Contents/Home)
  if [ -x "$1/Contents/Home/bin/java" ]; then echo "$1/Contents/Home"; else echo "$1"; fi
}

# Find a JDK >= 17: JAVA_HOME, then the toolchain, then java on PATH.
find_jdk() {
  JDK_HOME=; JDK_SOURCE=; JDK_MAJOR=
  local cands=() src=() h
  [ -n "${JAVA_HOME:-}" ] && { cands+=("$JAVA_HOME"); src+=("JAVA_HOME"); }
  h="$(jdk_home_in "$TOOLCHAIN/jdk17")"; cands+=("$h"); src+=("toolchain")
  if have java; then
    h="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p' | head -1)"
    [ -n "$h" ] && { cands+=("$h"); src+=("PATH"); }
  fi
  local i m
  for i in "${!cands[@]}"; do
    [ -x "${cands[$i]}/bin/java" ] || continue
    m="$(java_major "${cands[$i]}/bin/java")"
    if [ -n "$m" ] && [ "$m" -ge "$MIN_JAVA" ] 2>/dev/null; then
      JDK_HOME="${cands[$i]}"; JDK_SOURCE="${src[$i]}"; JDK_MAJOR="$m"; return 0
    fi
  done
  return 1
}

# Find an Android SDK: ANDROID_HOME, ANDROID_SDK_ROOT, local.properties, the toolchain, ~/Android/Sdk.
local_sdk_dir() { [ -f "$ROOT/local.properties" ] && sed -n 's/^sdk\.dir *= *//p' "$ROOT/local.properties" | head -1; }
find_sdk() {
  SDK=; SDK_SOURCE=
  local d
  for d in "${ANDROID_HOME:-}|ANDROID_HOME" "${ANDROID_SDK_ROOT:-}|ANDROID_SDK_ROOT" "$(local_sdk_dir)|local.properties" \
           "$TOOLCHAIN/android-sdk|toolchain" "$HOME/Android/Sdk|~/Android/Sdk" "$HOME/Library/Android/sdk|~/Library/Android/sdk"; do
    local path="${d%|*}" src="${d##*|}"
    if [ -n "$path" ] && [ -d "$path" ] && { [ -d "$path/platforms" ] || [ -d "$path/cmdline-tools" ] || [ -d "$path/platform-tools" ]; }; then
      SDK="$path"; SDK_SOURCE="$src"; return 0
    fi
  done
  return 1
}

sdkmanager_bin() { # newest cmdline-tools sdkmanager in $SDK
  local c
  for c in "$SDK/cmdline-tools/latest/bin/sdkmanager" "$SDK"/cmdline-tools/*/bin/sdkmanager "$SDK/tools/bin/sdkmanager"; do
    [ -x "$c" ] && { echo "$c"; return 0; }
  done
  return 1
}

run_sdkmanager() { # args...
  JAVA_HOME="$JDK_HOME" "$(sdkmanager_bin)" --sdk_root="$SDK" "$@"
}

write_env_sh() {
  mkdir -p "$TOOLCHAIN"
  cat > "$TOOLCHAIN/env.sh" <<EOF
# Generated by soloton scripts/doctor.sh --fix. Source it before building: . "$TOOLCHAIN/env.sh"
export JAVA_HOME="$JDK_HOME"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
EOF
}

# ---------- fixes ----------
fix_jdk() {
  [ "$CAN_DOWNLOAD" = 1 ] || { hint "no automatic JDK download for $OS/$ARCH; install JDK $MIN_JAVA+ and set JAVA_HOME"; return 1; }
  have curl && have tar && have python3 || { hint "needs curl, tar and python3"; return 1; }
  fixing "downloading Temurin JDK $MIN_JAVA ($ADOPT_OS/$ADOPT_ARCH) from Adoptium"
  local tmp meta link sum
  tmp="$(mktemp -d)" || return 1
  meta="$(curl -fsSL "https://api.adoptium.net/v3/assets/latest/$MIN_JAVA/hotspot?architecture=$ADOPT_ARCH&image_type=jdk&os=$ADOPT_OS&vendor=eclipse")" || { rm -rf "$tmp"; return 1; }
  link="$(printf '%s' "$meta" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["link"])')" &&
  sum="$(printf '%s' "$meta" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["checksum"])')" || { rm -rf "$tmp"; return 1; }
  curl -fsSL -o "$tmp/jdk.tgz" "$link" || { rm -rf "$tmp"; return 1; }
  if [ "$(sha_of sha256 "$tmp/jdk.tgz")" != "$sum" ]; then
    hint "checksum mismatch for $link; not installed"; rm -rf "$tmp"; return 1
  fi
  rm -rf "$TOOLCHAIN/jdk17" && mkdir -p "$TOOLCHAIN/jdk17" &&
  tar -xzf "$tmp/jdk.tgz" -C "$TOOLCHAIN/jdk17" --strip-components=1
  local rc=$?; rm -rf "$tmp"; return $rc
}

fix_cmdline_tools() { # installs into $SDK (or the toolchain SDK when none was found)
  [ -n "$SDK_OS" ] || { hint "no automatic SDK download for $OS"; return 1; }
  have curl && have unzip && have python3 || { hint "needs curl, unzip and python3"; return 1; }
  [ -n "$SDK" ] || { SDK="$TOOLCHAIN/android-sdk"; SDK_SOURCE=toolchain; }
  fixing "downloading Android command-line tools from Google into $SDK"
  local tmp url sum archive
  tmp="$(mktemp -d)" || return 1
  curl -fsSL -o "$tmp/repo.xml" https://dl.google.com/android/repository/repository2-3.xml || { rm -rf "$tmp"; return 1; }
  read -r archive sum < <(python3 -I - "$tmp/repo.xml" "$SDK_OS" "$ARCH" <<'PY'
import sys, xml.etree.ElementTree as ET
root, want_os, arch = ET.parse(sys.argv[1]).getroot(), sys.argv[2], sys.argv[3]
for p in root.iter("remotePackage"):
    if p.get("path") != "cmdline-tools;latest":
        continue
    best = None
    for a in p.iter("archive"):
        if a.findtext("host-os") != want_os:
            continue
        c = a.find("complete")
        url, sha = c.findtext("url"), c.findtext("checksum")
        if want_os == "macosx" and ("arm64" in url) != (arch in ("arm64", "aarch64")):
            continue
        best = (url, sha)
    if best:
        print(*best)
        break
PY
)
  [ -n "${archive:-}" ] || { hint "could not find command-line tools for $SDK_OS in Google's index"; rm -rf "$tmp"; return 1; }
  url="https://dl.google.com/android/repository/$archive"
  curl -fsSL -o "$tmp/clt.zip" "$url" || { rm -rf "$tmp"; return 1; }
  if [ "$(sha_of sha1 "$tmp/clt.zip")" != "$sum" ]; then
    hint "checksum mismatch for $url; not installed"; rm -rf "$tmp"; return 1
  fi
  mkdir -p "$SDK/cmdline-tools" && rm -rf "$SDK/cmdline-tools/latest" "$tmp/x" &&
  unzip -q "$tmp/clt.zip" -d "$tmp/x" && mv "$tmp/x/cmdline-tools" "$SDK/cmdline-tools/latest"
  local rc=$?; rm -rf "$tmp"; return $rc
}

fix_licenses() {
  fixing "accepting the Android SDK licences with sdkmanager --licenses"
  local out
  out="$( (yes 2>/dev/null || true) | run_sdkmanager --licenses 2>&1)"
  [ -f "$SDK/licenses/android-sdk-license" ] && return 0
  # Newer command-line tools drop --licenses and record the licence while installing packages.
  if printf '%s' "$out" | grep -qi 'no longer needed'; then LICENSE_ON_INSTALL=1; return 0; fi
  return 1
}

fix_packages() { # package...
  fixing "sdkmanager --install $*"
  run_sdkmanager --install "$@" >/dev/null 2>&1
}

fix_local_properties() {
  fixing "writing sdk.dir=$SDK to local.properties (git-ignored)"
  if [ -f "$ROOT/local.properties" ] && grep -q '^sdk\.dir' "$ROOT/local.properties"; then
    sed -i.bak "s|^sdk\.dir *=.*|sdk.dir=$SDK|" "$ROOT/local.properties" && rm -f "$ROOT/local.properties.bak"
  else
    printf 'sdk.dir=%s\n' "$SDK" >> "$ROOT/local.properties"
  fi
}

fix_avd() {
  fixing "creating the $AVD_NAME emulator with scripts/gen1-emulator.sh --create"
  JAVA_HOME="$JDK_HOME" ANDROID_HOME="$SDK" "$ROOT/scripts/gen1-emulator.sh" --create >/dev/null 2>&1
}

# =====================================================================
printf '%sSoloton doctor%s  %s%s %s · %s%s\n' "$C_BOLD" "$C_OFF" "$C_DIM" "$OS" "$ARCH" "$( [ "$FIX" = 1 ] && echo 'fixing' || echo 'report only')" "$C_OFF"

# ---------- basics ----------
section "Basics"
if have git; then ok "git" "$(git --version | cut -d' ' -f3)"; else fail "git" "not found"; hint "install git"; fi
for t in curl unzip tar; do
  if have "$t"; then ok "$t" "found"; else warn "$t" "not found (needed by --fix downloads)"; hint "install $t with your package manager"; fi
done
if have python3; then ok "python3" "$(python3 -V 2>&1 | cut -d' ' -f2)"; else warn "python3" "not found (needed by the progress site build and --fix)"; hint "install python3"; fi
if [ -x "$ROOT/gradlew" ]; then
  ok "Gradle wrapper" "gradlew${GRADLE_VERSION:+, Gradle $GRADLE_VERSION (downloads on first run)}"
elif [ -f "$ROOT/gradlew" ]; then
  fail "Gradle wrapper" "gradlew is not executable"; autofix
  if [ "$FIX" = 1 ]; then chmod +x "$ROOT/gradlew" && { fixed; FAILS=$((FAILS - 1)); ok "Gradle wrapper" "made executable"; }; fi
else
  fail "Gradle wrapper" "gradlew missing"; hint "restore it with git checkout -- gradlew"
fi

# ---------- JDK ----------
section "Java"
if find_jdk; then
  ok "JDK $MIN_JAVA+" "Java $JDK_MAJOR from $JDK_SOURCE ($JDK_HOME)"
else
  fail "JDK $MIN_JAVA+" "no JDK $MIN_JAVA or newer found in JAVA_HOME, $TOOLCHAIN/jdk17 or PATH"
  if [ "$FIX" = 1 ]; then
    if fix_jdk && find_jdk; then fixed; FAILS=$((FAILS - 1)); ok "JDK $MIN_JAVA+" "installed Java $JDK_MAJOR at $JDK_HOME"; fi
  else
    autofix; hint "or install any JDK $MIN_JAVA+ (Temurin) and set JAVA_HOME"
  fi
fi
# ./gradlew starts with JAVA_HOME, or java on PATH; it cannot find the toolchain JDK by itself.
# (Repo scripts such as gen1-emulator.sh and screenshots.sh find it on their own.)
shell_jdk_major() {
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then java_major "$JAVA_HOME/bin/java"
  elif have java; then java_major "$(command -v java)"; fi
}
if [ -n "$JDK_HOME" ]; then
  m="$(shell_jdk_major)"
  if [ -n "$m" ] && [ "$m" -ge "$MIN_JAVA" ] 2>/dev/null; then
    ok "JDK visible to Gradle" "Java $m via $( [ -n "${JAVA_HOME:-}" ] && echo JAVA_HOME || echo PATH )"
  else
    fail "JDK visible to Gradle" "./gradlew will not start in this shell: ${m:+java on it is $m; }no JAVA_HOME for JDK $MIN_JAVA+"
    hint "this shell:   . \"$TOOLCHAIN/env.sh\""
    if [ "$SHELLRC" = 1 ] && [ "$FIX" = 1 ]; then hint "every shell:  open a new terminal once --shell below has added the line"
    else hint "every shell:  scripts/doctor.sh --fix --shell   (adds that line to your shell startup file)"; fi
    hint "repo scripts (gen1-emulator.sh, screenshots.sh) find the JDK themselves"
  fi
fi

# ---------- Android SDK ----------
section "Android SDK (compileSdk $COMPILE_SDK, build-tools $BUILD_TOOLS)"
SDK_OK=0
if find_sdk; then
  ok "SDK" "$SDK (from $SDK_SOURCE)"; SDK_OK=1
else
  fail "SDK" "no Android SDK found in ANDROID_HOME, local.properties, $TOOLCHAIN/android-sdk or ~/Android/Sdk"
  [ "$FIX" = 1 ] || { autofix; hint "or install Android Studio / the command-line tools and set ANDROID_HOME"; }
fi

if [ "$SDK_OK" = 1 ] && sdkmanager_bin >/dev/null; then
  ok "command-line tools" "$(sdkmanager_bin | sed "s|$SDK/||")"
else
  [ "$SDK_OK" = 1 ] && fail "command-line tools" "sdkmanager not found in the SDK"
  if [ "$FIX" = 1 ] && [ -n "$JDK_HOME" ]; then
    if fix_cmdline_tools && sdkmanager_bin >/dev/null; then
      fixed; FAILS=$((FAILS - 1))   # clears either the "SDK" or the "command-line tools" failure
      SDK_OK=1; ok "command-line tools" "installed into $SDK"
    fi
  elif [ "$SDK_OK" = 1 ]; then autofix; fi
fi

if [ "$SDK_OK" = 1 ] && sdkmanager_bin >/dev/null && [ -n "$JDK_HOME" ]; then
  if [ -f "$SDK/licenses/android-sdk-license" ]; then ok "SDK licences" "accepted"
  else
    fail "SDK licences" "not accepted"
    if [ "$FIX" = 1 ] && fix_licenses; then
      fixed; FAILS=$((FAILS - 1))
      if [ "${LICENSE_ON_INSTALL:-0}" = 1 ]; then ok "SDK licences" "accepted when packages install (this sdkmanager has no --licenses)"
      else ok "SDK licences" "accepted"; fi
    else autofix; fi
  fi
  missing=()
  [ -f "$SDK/platforms/android-$COMPILE_SDK/android.jar" ] && ok "$PKG_PLATFORM" "installed" || { fail "$PKG_PLATFORM" "missing"; missing+=("$PKG_PLATFORM"); }
  [ -x "$SDK/build-tools/$BUILD_TOOLS/aapt2" ] && ok "$PKG_BUILD_TOOLS" "installed" || { fail "$PKG_BUILD_TOOLS" "missing"; missing+=("$PKG_BUILD_TOOLS"); }
  [ -x "$SDK/platform-tools/adb" ] && ok "platform-tools" "adb $("$SDK/platform-tools/adb" version 2>/dev/null | sed -n 's/^Version //p')" || { fail "platform-tools" "missing"; missing+=("platform-tools"); }
  if [ "${#missing[@]}" -gt 0 ]; then
    if [ "$FIX" = 1 ]; then
      if fix_packages "${missing[@]}"; then fixed; FAILS=$((FAILS - ${#missing[@]})); ok "SDK packages" "installed ${missing[*]}"; fi
    else autofix; fi
  fi
elif [ "$SDK_OK" = 1 ] && [ -z "$JDK_HOME" ]; then
  warn "SDK packages" "not checked: sdkmanager needs a JDK first"
fi

# Can Gradle find the SDK on its own?
if [ "$SDK_OK" = 1 ]; then
  if [ -n "${ANDROID_HOME:-}" ] && [ "$ANDROID_HOME" = "$SDK" ]; then ok "SDK visible to Gradle" "via ANDROID_HOME"
  elif [ "$(local_sdk_dir)" = "$SDK" ]; then ok "SDK visible to Gradle" "via local.properties"
  else
    fail "SDK visible to Gradle" "neither ANDROID_HOME nor local.properties points at $SDK"
    if [ "$FIX" = 1 ]; then fix_local_properties && { fixed; FAILS=$((FAILS - 1)); ok "SDK visible to Gradle" "via local.properties"; }
    else autofix; hint "or export ANDROID_HOME=\"$SDK\""; fi
  fi
fi

# Keep the toolchain env file in step with what was found.
if [ "$FIX" = 1 ] && [ -n "$JDK_HOME" ] && [ "$SDK_OK" = 1 ]; then
  write_env_sh && ok "env file" "$TOOLCHAIN/env.sh"
  if [ "$SHELLRC" = 1 ]; then
    case "$(basename "${SHELL:-}")" in zsh) RC="$HOME/.zshrc" ;; bash) RC="$HOME/.bashrc" ;; *) RC="$HOME/.profile" ;; esac
    LINE="[ -f \"$TOOLCHAIN/env.sh\" ] && . \"$TOOLCHAIN/env.sh\"   # soloton toolchain (scripts/doctor.sh --shell)"
    if [ -f "$RC" ] && grep -qF "# soloton toolchain" "$RC"; then
      ok "shell startup" "$RC already loads the toolchain"
    else
      printf '\n%s\n' "$LINE" >> "$RC" && ok "shell startup" "added the toolchain to $RC (new shells)"
    fi
  fi
elif [ "$SHELLRC" = 1 ] && [ "$FIX" = 0 ]; then
  hint "--shell only takes effect together with --fix"
fi

# ---------- emulator (optional) ----------
section "Gen 1 tablet emulator (optional: local device testing, docs/DEVICE.md)"
if [ "$EMULATOR" = 0 ]; then
  printf '  %sskipped (--no-emulator)%s\n' "$C_DIM" "$C_OFF"
elif [ "$SDK_OK" = 1 ] && sdkmanager_bin >/dev/null && [ -n "$JDK_HOME" ]; then
  emu_missing=()
  [ -x "$SDK/emulator/emulator" ] && ok "emulator" "$("$SDK/emulator/emulator" -version 2>/dev/null | sed -n 's/.*version \([0-9.]*\).*/\1/p' | head -1)" || { warn "emulator" "not installed"; emu_missing+=("emulator"); }
  if [ -z "$IMG_ABI" ]; then warn "system image" "no API 24 image for $ARCH"
  elif [ -d "$SDK/system-images/android-24/default/$IMG_ABI" ]; then ok "$PKG_IMAGE" "installed"
  else warn "$PKG_IMAGE" "not installed"; emu_missing+=("$PKG_IMAGE"); fi
  if [ "${#emu_missing[@]}" -gt 0 ]; then
    if [ "$FIX" = 1 ]; then fix_packages "${emu_missing[@]}" && { fixed; WARNS=$((WARNS - ${#emu_missing[@]})); ok "emulator packages" "installed ${emu_missing[*]}"; }
    else autofix; fi
  fi
  if [ -f "$AVD_HOME/$AVD_NAME.avd/config.ini" ] && grep -q '^hw.lcd.density *= *160' "$AVD_HOME/$AVD_NAME.avd/config.ini"; then
    ok "AVD $AVD_NAME" "1920x1080, 160 dpi, 2 GB"
  else
    warn "AVD $AVD_NAME" "not created"
    if [ "$FIX" = 1 ] && [ -x "$SDK/emulator/emulator" ]; then fix_avd && { fixed; WARNS=$((WARNS - 1)); ok "AVD $AVD_NAME" "created"; }
    else autofix; fi
  fi
else
  warn "emulator" "not checked: needs the JDK and SDK first"
fi
if [ "$EMULATOR" = 1 ] && [ "$OS" = Linux ]; then
  if [ -r /dev/kvm ] && [ -w /dev/kvm ]; then ok "KVM" "/dev/kvm usable (fast emulator)"
  elif [ -e /dev/kvm ]; then warn "KVM" "/dev/kvm exists but this user cannot use it"; hint "sudo usermod -aG kvm \"$USER\"   then log out and back in"
  else warn "KVM" "no /dev/kvm: enable virtualisation in the BIOS/UEFI; the emulator will be very slow without it"; fi
fi

# ---------- screenshots and site (optional) ----------
section "Desktop screenshots and progress site (optional)"
if [ "$OS" = Linux ]; then
  if [ -n "${DISPLAY:-}" ]; then ok "display" "DISPLAY=$DISPLAY"; fi
  if have xvfb-run; then ok "xvfb-run" "found (headless screenshots)"
  else warn "xvfb-run" "not found"; hint "sudo apt-get install xvfb   (or your distro's equivalent)"; fi
  if have ldconfig && ldconfig -p 2>/dev/null | grep -q 'libGL\.so\.1'; then ok "OpenGL (Mesa)" "libGL found"
  else warn "OpenGL (Mesa)" "libGL not found"; hint "sudo apt-get install libgl1-mesa-dri"; fi
fi

# ---------- GitHub (optional) ----------
section "GitHub (optional: releases, CI status)"
if have gh; then
  if gh auth status >/dev/null 2>&1; then ok "gh" "logged in"
  else warn "gh" "installed but not logged in"; hint "gh auth login"; fi
else
  warn "gh" "not found"; hint "install the GitHub CLI: https://cli.github.com"
fi
if git -C "$ROOT" remote get-url origin >/dev/null 2>&1; then ok "git remote" "$(git -C "$ROOT" remote get-url origin)"
else warn "git remote" "no origin remote"; fi

# ---------- summary ----------
[ "$FAILS" -lt 0 ] && FAILS=0; [ "$WARNS" -lt 0 ] && WARNS=0
printf '\n'
if [ "$FAILS" -eq 0 ]; then
  printf '%sReady to build.%s' "$C_OK" "$C_OFF"
else
  printf '%s%d required check(s) failing.%s' "$C_FAIL" "$FAILS" "$C_OFF"
fi
[ "$WARNS" -gt 0 ] && printf ' %d optional warning(s).' "$WARNS"
[ "$FIXED" -gt 0 ] && printf ' Applied %d fix(es).' "$FIXED"
printf '\n'
if [ "$FAILS" -gt 0 ] && [ "$FIX" = 0 ]; then printf 'Run %sscripts/doctor.sh --fix%s to apply automatic fixes.\n' "$C_BOLD" "$C_OFF"; fi
if [ "$FIX" = 1 ] && [ -f "$TOOLCHAIN/env.sh" ] && [ "${JAVA_HOME:-}" != "$JDK_HOME" ]; then
  printf 'Before building in this shell: %s. "%s/env.sh"%s\n' "$C_BOLD" "$TOOLCHAIN" "$C_OFF"
fi
[ "$FAILS" -eq 0 ]
