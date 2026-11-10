#!/usr/bin/env bash
# Assemble the progress site into an output directory (default: _site).
#   1. copy site/
#   2. copy committed screenshots from apps/<app>/docs/*.png
#   3. if apps/<app>/screenshots.sh exists, run it to capture fresh screenshots
#      into the given directory (best effort; failures do not break the site)
#   4. write screenshots/manifest.json for the page
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${1:-_site}"
rm -rf "$OUT"
mkdir -p "$OUT/screenshots"
cp -R site/. "$OUT/"

for docs in apps/*/docs; do
  [ -d "$docs" ] || continue
  app=$(basename "$(dirname "$docs")")
  for png in "$docs"/*.png; do
    [ -f "$png" ] || continue
    cp "$png" "$OUT/screenshots/$app--$(basename "$png")"
  done
done

if [ "${CAPTURE:-1}" = "1" ]; then
  for capture in apps/*/screenshots.sh; do
    [ -f "$capture" ] || continue
    app=$(basename "$(dirname "$capture")")
    dir="$OUT/screenshots/live-$app"
    mkdir -p "$dir"
    if ! bash "$capture" "$(pwd)/$dir"; then
      echo "::warning::screenshot capture failed for $app"
    fi
  done
fi

python3 - "$OUT" <<'PY'
import json, os, sys
out = sys.argv[1]
root = os.path.join(out, "screenshots")
shots = []
for dirpath, _, files in os.walk(root):
    for f in sorted(files):
        if not f.lower().endswith(".png"):
            continue
        rel = os.path.relpath(os.path.join(dirpath, f), out)
        name = os.path.splitext(f)[0]
        sub = os.path.relpath(dirpath, root)
        if sub.startswith("live-"):
            caption = f"{sub[5:]}: {name.replace('-', ' ')} (latest build)"
        else:
            app, _, rest = name.partition("--")
            caption = f"{app}: {rest.replace('-', ' ')}"
        shots.append({"src": rel.replace(os.sep, "/"), "caption": caption})
shots.sort(key=lambda s: (not s["caption"].endswith("(latest build)"), s["caption"]))
with open(os.path.join(root, "manifest.json"), "w") as fh:
    json.dump(shots, fh, indent=1)
print(f"{len(shots)} screenshot(s)")
PY
