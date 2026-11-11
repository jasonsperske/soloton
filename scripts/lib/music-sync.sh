# Sourced by scripts/gen1-emulator.sh. Copies a local music folder to a device with adb:
# audio files only, sub-folders kept, files already there with the same size skipped.
#
#   . "$ROOT/scripts/lib/music-sync.sh"
#   soloton_music_sync <adb binary> <serial> <local dir> <device dir>
#
# Names are passed around one per line and never word-split or glob-expanded, so apostrophes,
# "!!", spaces and non-ASCII are fine. A name containing a newline is not supported.

SOLOTON_MUSIC_EXTENSIONS="mp3 ogg opus m4a aac wav flac"

# Single-quotes a string for the device's shell.
soloton_shell_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

# Prints "<size><TAB><path relative to dir>" for every audio file under <dir>, sorted by path.
# Hidden files and files inside hidden folders are skipped.
soloton_music_local_list() {
  local src="$1" ext
  local -a match=()
  for ext in $SOLOTON_MUSIC_EXTENSIONS; do
    [ "${#match[@]}" -gt 0 ] && match+=(-o)
    match+=(-iname "*.$ext")
  done
  (
    cd "$src" || exit 1
    if find . -maxdepth 0 -printf '' >/dev/null 2>&1; then
      find . -type f ! -path '*/.*' \( "${match[@]}" \) -printf '%s\t%P\n'
    else
      # BSD find (macOS) has no -printf.
      find . -type f ! -path '*/.*' \( "${match[@]}" \) | while IFS= read -r f; do
        printf '%s\t%s\n' "$(wc -c < "$f" | tr -d ' ')" "${f#./}"
      done
    fi
  ) | LC_ALL=C sort -t "$(printf '\t')" -k2
}

# Prints "<size> <path relative to dir>" for every file under <dir> on the device. Nothing if it does not exist.
soloton_music_remote_list() {
  local adb="$1" serial="$2" dest="$3" q
  q="$(soloton_shell_quote "$dest")"
  "$adb" -s "$serial" shell "if [ -d $q ]; then cd $q && find . -type f -exec stat -c '%s %n' {} +; fi" 2>/dev/null \
    | tr -d '\r' | sed 's|^\([0-9]*\) \./|\1 |'
}

# Reads a local list (file $1) and a remote list (file $2); prints the relative paths that need
# copying: not on the device, or there with a different size.
soloton_music_plan() {
  awk -F '\t' -v remote="$2" '
    BEGIN {
      while ((getline line < remote) > 0) {
        i = index(line, " ")
        if (i > 0) have[substr(line, i + 1)] = substr(line, 1, i - 1)
      }
    }
    {
      path = substr($0, index($0, "\t") + 1)
      if (!(path in have) || have[path] != $1) print path
    }
  ' "$1"
}

soloton_music_sync() {
  local adb="$1" serial="$2" src="$3" dest="$4"
  local tmp total copy already dir path last_dir mkdirs
  local -a batch=()
  src="${src%/}"
  dest="${dest%/}"
  tmp="$(mktemp -d)" || return 1
  soloton_music_local_list "$src" > "$tmp/local"
  soloton_music_remote_list "$adb" "$serial" "$dest" > "$tmp/remote"
  soloton_music_plan "$tmp/local" "$tmp/remote" > "$tmp/copy"
  total="$(wc -l < "$tmp/local" | tr -d ' ')"
  copy="$(wc -l < "$tmp/copy" | tr -d ' ')"
  already=$((total - copy))

  if [ "$copy" -gt 0 ]; then
    # Every folder that will receive a file, made in one device shell call.
    mkdirs="mkdir -p $(soloton_shell_quote "$dest")"
    while IFS= read -r dir; do
      mkdirs="$mkdirs $(soloton_shell_quote "$dest/$dir")"
    done < <(sed -n 's|/[^/]*$||p' "$tmp/copy" | LC_ALL=C sort -u)
    "$adb" -s "$serial" shell "$mkdirs" || { rm -rf "$tmp"; return 1; }

    # One adb push per folder: far fewer round trips than one per file.
    last_dir=$'\n'
    flush() {
      [ "${#batch[@]}" -eq 0 ] && return 0
      local target="$dest/"
      [ "$last_dir" != "." ] && target="$dest/$last_dir/"
      # adb reports every file on stderr; keep that quiet unless the push fails.
      "$adb" -s "$serial" push "${batch[@]}" "$target" >/dev/null 2>"$tmp/push.log" || { tail -n 3 "$tmp/push.log" >&2; return 1; }
      batch=()
    }
    while IFS= read -r path; do
      case "$path" in */*) dir="${path%/*}" ;; *) dir="." ;; esac
      if [ "$dir" != "$last_dir" ]; then
        flush || { rm -rf "$tmp"; echo "Music sync failed while copying to $dest" >&2; return 1; }
        last_dir="$dir"
      fi
      batch+=("$src/$path")
    done < "$tmp/copy"
    flush || { rm -rf "$tmp"; echo "Music sync failed while copying to $dest" >&2; return 1; }
  fi
  rm -rf "$tmp"
  echo "Music sync: $copy copied, $already already there ($serial:$dest)"
}
