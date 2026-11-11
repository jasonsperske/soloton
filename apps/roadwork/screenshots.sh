#!/usr/bin/env bash
# Renders Roadwork desktop screenshots into a directory.
#
#   apps/roadwork/screenshots.sh <absolute output dir>
#
# Run from the repo root. Needs JDK 17+ and xvfb-run (with Mesa for software GL) when there
# is no display. Each shot advances simulated time with a fixed 1/60 s step, so output is
# deterministic for a given build. Exits non-zero if any shot fails.
#
# The shots use the built-in demo music names. SOLOTON_MUSIC_DIR (see .env.example) is ignored
# here on purpose, so every machine produces the same images and nobody's own library shows up
# in a committed screenshot.
set -euo pipefail

if [[ $# -ne 1 || "$1" != /* ]]; then
  echo "usage: $0 <absolute output dir>" >&2
  exit 2
fi
out="$1"
mkdir -p "$out"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
# Find the JDK (and SDK) without needing JAVA_HOME exported; see scripts/doctor.sh.
. "$repo_root/scripts/lib/toolchain.sh"
soloton_toolchain_env "$repo_root"

# name:seconds pairs: the start, settled in, five minutes on, and twenty minutes (end of the light drift).
shots=(
  "scene-at-0s:0"
  "scene-at-30s:30"
  "scene-at-300s:300"
  "scene-at-1200s:1200"
)

./gradlew --quiet :apps:roadwork:desktop:installDist
launcher="$repo_root/apps/roadwork/desktop/build/install/roadwork-desktop/bin/roadwork-desktop"

run() {
  if [[ -n "${DISPLAY:-}" && -z "${SOLOTON_FORCE_XVFB:-}" ]]; then
    "$@"
  else
    # -a picks a free display number so concurrent runs do not collide.
    xvfb-run -a -s "-screen 0 1280x1024x24" "$@"
  fi
}

# Never the developer's own music folder: these images are committed.
unset SOLOTON_MUSIC_DIR SOLOTON_MUSIC_DEVICE_DIR

# Software GL is fine (and the only option on CI runners).
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"

for shot in "${shots[@]}"; do
  name="${shot%%:*}"
  at="${shot##*:}"
  run "$launcher" --screenshot "$out/$name.png" --at "$at"
  [[ -s "$out/$name.png" ]] || { echo "missing $out/$name.png" >&2; exit 1; }
done

# Skylift Mesa: on the ground with no resistance, then lifted by a middling and a high resistance.
for shot in "skylift-mesa-ground:0" "skylift-mesa-climbing:45" "skylift-mesa-high:85"; do
  name="${shot%%:*}"
  resistance="${shot##*:}"
  run "$launcher" --env skylift-mesa --resistance "$resistance" --screenshot "$out/$name.png" --at 30
  [[ -s "$out/$name.png" ]] || { echo "missing $out/$name.png" >&2; exit 1; }
done

# Pedalcraft Island: the island from above, then each craft in flight (name:seconds:resistance).
# At the default pace the glider flies for the first 24 minutes, the paramotor for the next 22
# and the plane for 20 after that.
run "$launcher" --env pedalcraft-island --view overview --screenshot "$out/pedalcraft-island-overview.png" --at 0
[[ -s "$out/pedalcraft-island-overview.png" ]] || { echo "missing $out/pedalcraft-island-overview.png" >&2; exit 1; }
for shot in "pedalcraft-island-glider:400:60" "pedalcraft-island-landing:1432:0" "pedalcraft-island-paramotor:1800:40" "pedalcraft-island-plane:3200:70"; do
  IFS=: read -r name at resistance <<< "$shot"
  run "$launcher" --env pedalcraft-island --resistance "$resistance" --screenshot "$out/$name.png" --at "$at"
  [[ -s "$out/$name.png" ]] || { echo "missing $out/$name.png" >&2; exit 1; }
done

# The environment picker, the screen the app opens on.
run "$launcher" --picker --screenshot "$out/picker.png"
[[ -s "$out/picker.png" ]] || { echo "missing $out/picker.png" >&2; exit 1; }

echo "screenshots written to $out"
