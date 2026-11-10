# Sourced by repo scripts so they work from any shell, without exporting anything first.
# Fills in JAVA_HOME and ANDROID_HOME when they are unset or invalid, searching the same
# places as scripts/doctor.sh. Run scripts/doctor.sh --fix if nothing is found.
#
#   . "$ROOT/scripts/lib/toolchain.sh"; soloton_toolchain_env "$ROOT"

soloton_toolchain_env() {
  local root="$1" tc="${SOLOTON_TOOLCHAIN:-$HOME/.local/share/soloton-toolchain}" d sdkdir

  # JDK: a valid JAVA_HOME wins; then the toolchain JDK; otherwise leave java on PATH to Gradle.
  if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
    for d in "$tc/jdk17/Contents/Home" "$tc/jdk17"; do
      if [ -x "$d/bin/java" ]; then export JAVA_HOME="$d"; break; fi
    done
  fi
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  # Android SDK: ANDROID_HOME, ANDROID_SDK_ROOT, local.properties, the toolchain, the usual Studio paths.
  if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "$ANDROID_HOME" ]; then
    sdkdir=""
    [ -f "$root/local.properties" ] && sdkdir="$(sed -n 's/^sdk\.dir *= *//p' "$root/local.properties" | head -1)"
    for d in "${ANDROID_SDK_ROOT:-}" "$sdkdir" "$tc/android-sdk" "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
      if [ -n "$d" ] && [ -d "$d" ]; then export ANDROID_HOME="$d"; break; fi
    done
  fi
  if [ -n "${ANDROID_HOME:-}" ]; then
    export ANDROID_SDK_ROOT="$ANDROID_HOME"
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
  fi
}
