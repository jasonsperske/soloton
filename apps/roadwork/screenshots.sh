#!/usr/bin/env bash
# Renders Roadwork desktop screenshots into a directory.
#
#   apps/roadwork/screenshots.sh <absolute output dir>
#
# Run from the repo root. Needs JDK 17+ and xvfb-run (with Mesa for software GL) when there
# is no display. Each shot advances simulated time with a fixed 1/60 s step, so output is
# deterministic for a given build. Exits non-zero if any shot fails.
set -euo pipefail

if [[ $# -ne 1 || "$1" != /* ]]; then
  echo "usage: $0 <absolute output dir>" >&2
  exit 2
fi
out="$1"
mkdir -p "$out"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

# name:seconds pairs: first light, settled in, five minutes on, and full morning (20 min).
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

# Software GL is fine (and the only option on CI runners).
export LIBGL_ALWAYS_SOFTWARE="${LIBGL_ALWAYS_SOFTWARE:-1}"

for shot in "${shots[@]}"; do
  name="${shot%%:*}"
  at="${shot##*:}"
  run "$launcher" --screenshot "$out/$name.png" --at "$at"
  [[ -s "$out/$name.png" ]] || { echo "missing $out/$name.png" >&2; exit 1; }
done

echo "screenshots written to $out"
