#!/usr/bin/env bash
# Tests for the shell helpers in scripts/lib: the .env loader and the music sync planning.
# Plain bash assertions, no test framework, no emulator. CI runs it in the check job.
#
#   scripts/test.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/scripts/lib/dotenv.sh"
. "$ROOT/scripts/lib/music-sync.sh"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
PASS=0; FAIL=0

eq() { # description expected actual
  if [ "$2" = "$3" ]; then PASS=$((PASS + 1))
  else FAIL=$((FAIL + 1)); printf 'FAIL: %s\n  expected: [%s]\n  actual:   [%s]\n' "$1" "$2" "$3"; fi
}

# Loads a .env with the given contents in a clean subshell and prints the named variable, or
# "<unset>". Extra arguments are VAR=value pairs put in the environment first.
load() { # contents variable [VAR=value...]
  local contents="$1" name="$2"; shift 2
  printf '%s' "$contents" > "$TMP/.env"
  (
    unset "${!SOLOTON_@}" 2>/dev/null
    for pair in "$@"; do export "$pair"; done
    soloton_load_dotenv "$TMP/.env"
    if [ -n "${!name+set}" ]; then printf '%s' "${!name}"; else printf '<unset>'; fi
  )
}

# ---------- dotenv: plain values ----------
eq "plain value"              "/music/games"      "$(load 'SOLOTON_MUSIC_DIR=/music/games' SOLOTON_MUSIC_DIR)"
eq "no trailing newline"      "/a"                "$(load 'SOLOTON_MUSIC_DIR=/a' SOLOTON_MUSIC_DIR)"
eq "trailing newline"         "/a"                "$(load $'SOLOTON_MUSIC_DIR=/a\n' SOLOTON_MUSIC_DIR)"
eq "windows line ending"      "/a"                "$(load $'SOLOTON_MUSIC_DIR=/a\r\n' SOLOTON_MUSIC_DIR)"
eq "empty value is set"       ""                  "$(load 'SOLOTON_MUSIC_DIR=' SOLOTON_MUSIC_DIR)"
eq "spaces inside a value"    "/my music/all games" "$(load 'SOLOTON_MUSIC_DIR=/my music/all games' SOLOTON_MUSIC_DIR)"
eq "spaces around are trimmed" "/a b"             "$(load '   SOLOTON_MUSIC_DIR =   /a b   ' SOLOTON_MUSIC_DIR)"
eq "value containing ="       "a=b=c"             "$(load 'SOLOTON_X=a=b=c' SOLOTON_X)"
eq "export prefix"            "/a"                "$(load 'export SOLOTON_MUSIC_DIR=/a' SOLOTON_MUSIC_DIR)"
eq "a repeated name keeps its first value" "/first" "$(load $'SOLOTON_MUSIC_DIR=/first\nSOLOTON_MUSIC_DIR=/second\n' SOLOTON_MUSIC_DIR)"
eq "several variables"        "/dev/dir"          "$(load $'SOLOTON_MUSIC_DIR=/a\nSOLOTON_MUSIC_DEVICE_DIR=/dev/dir\n' SOLOTON_MUSIC_DEVICE_DIR)"

# ---------- dotenv: quotes ----------
eq "double quotes"            "/my music/games"   "$(load 'SOLOTON_MUSIC_DIR="/my music/games"' SOLOTON_MUSIC_DIR)"
eq "single quotes"            "/my music/games"   "$(load "SOLOTON_MUSIC_DIR='/my music/games'" SOLOTON_MUSIC_DIR)"
eq "apostrophe in double quotes" "/it's here!!"   "$(load 'SOLOTON_MUSIC_DIR="/it'"'"'s here!!"' SOLOTON_MUSIC_DIR)"
eq "apostrophe unquoted"      "/it's here!!"      "$(load "SOLOTON_MUSIC_DIR=/it's here!!" SOLOTON_MUSIC_DIR)"
eq "hash kept inside quotes"  "/a #b"             "$(load 'SOLOTON_MUSIC_DIR="/a #b"' SOLOTON_MUSIC_DIR)"
eq "quoted empty"             ""                  "$(load 'SOLOTON_MUSIC_DIR=""' SOLOTON_MUSIC_DIR)"
eq "lone quote is literal"    '"'                 "$(load 'SOLOTON_X="' SOLOTON_X)"
eq "non-ascii"                "/música/ゲーム"     "$(load 'SOLOTON_MUSIC_DIR=/música/ゲーム' SOLOTON_MUSIC_DIR)"

# ---------- dotenv: comments and blanks ----------
eq "comment lines and blanks" "/a"                "$(load $'# a comment\n\n   \n  # indented comment\nSOLOTON_MUSIC_DIR=/a\n' SOLOTON_MUSIC_DIR)"
eq "commented-out assignment" "<unset>"           "$(load '# SOLOTON_MUSIC_DIR=/a' SOLOTON_MUSIC_DIR)"
eq "inline comment"           "/a"                "$(load 'SOLOTON_MUSIC_DIR=/a   # where my music is' SOLOTON_MUSIC_DIR)"
eq "hash without a space is part of the value" "/a#1" "$(load 'SOLOTON_MUSIC_DIR=/a#1' SOLOTON_MUSIC_DIR)"
eq "comment as the whole value" ""                "$(load 'SOLOTON_MUSIC_DIR= # not set yet' SOLOTON_MUSIC_DIR)"
eq "line without ="           "<unset>"           "$(load 'SOLOTON_MUSIC_DIR' SOLOTON_MUSIC_DIR)"

# ---------- dotenv: only SOLOTON_ names ----------
eq "other names are ignored"  "<unset>"           "$(load 'MUSIC_DIR=/a' MUSIC_DIR)"
eq "PATH cannot be set"       "$PATH"             "$(load 'PATH=/nowhere' PATH)"
eq "lower case is ignored"    "<unset>"           "$(load 'soloton_music_dir=/a' soloton_music_dir)"
eq "prefix must be exact"     "<unset>"           "$(load 'XSOLOTON_A=/a' XSOLOTON_A)"
eq "odd characters in a name" "<unset>"           "$(load 'SOLOTON_A-B=/a' SOLOTON_A)"
eq "name with a space"        "<unset>"           "$(load 'SOLOTON_A B=/a' SOLOTON_A)"
eq "digits and underscores"   "v"                 "$(load 'SOLOTON_A_2=v' SOLOTON_A_2)"

# ---------- dotenv: the environment wins ----------
eq "existing value is kept"   "/from/env"         "$(load 'SOLOTON_MUSIC_DIR=/from/file' SOLOTON_MUSIC_DIR SOLOTON_MUSIC_DIR=/from/env)"
eq "existing empty value is kept" ""              "$(load 'SOLOTON_MUSIC_DIR=/from/file' SOLOTON_MUSIC_DIR SOLOTON_MUSIC_DIR=)"
eq "other variables still load" "/dev/dir"        "$(load $'SOLOTON_MUSIC_DIR=/f\nSOLOTON_MUSIC_DEVICE_DIR=/dev/dir' SOLOTON_MUSIC_DEVICE_DIR SOLOTON_MUSIC_DIR=/e)"

# ---------- dotenv: nothing is executed or expanded ----------
rm -f "$TMP/pwned"
eq "command substitution is literal" "\$(touch $TMP/pwned)" "$(load "SOLOTON_X=\$(touch $TMP/pwned)" SOLOTON_X)"
eq "backticks are literal"    "\`touch $TMP/pwned\`" "$(load "SOLOTON_X=\`touch $TMP/pwned\`" SOLOTON_X)"
eq "variables are not expanded" '$HOME/music'     "$(load 'SOLOTON_MUSIC_DIR=$HOME/music' SOLOTON_MUSIC_DIR)"
eq "a command line is ignored" "<unset>"          "$(load "touch $TMP/pwned; SOLOTON_X=1" SOLOTON_X)"
eq "semicolons are literal"   "1; touch $TMP/pwned" "$(load "SOLOTON_X=1; touch $TMP/pwned" SOLOTON_X)"
eq "nothing ran"              "no"                "$([ -e "$TMP/pwned" ] && echo yes || echo no)"
eq "missing file is fine"     "0"                 "$(soloton_load_dotenv "$TMP/does-not-exist"; echo $?)"

# ---------- the committed example ----------
eq ".env.example leaves the music dir empty" "" "$( (unset "${!SOLOTON_@}"; soloton_load_dotenv "$ROOT/.env.example"; printf '%s' "${SOLOTON_MUSIC_DIR-<unset>}") )"
eq ".env is git-ignored" ".env" "$(cd "$ROOT" && git check-ignore .env 2>/dev/null || echo 'not ignored')"

# ---------- music sync: what gets listed ----------
LIB="$TMP/library"
A="Demo Album's Greatest Hits!!"
mkdir -p "$LIB/$A" "$LIB/Demo Album B/Disc 2" "$LIB/Démo Álbum Ç" "$LIB/.hidden" "$LIB/Empty"
printf 'aaaa' > "$LIB/$A/01 - Demo Song One.mp3"
printf 'bb'   > "$LIB/$A/02 - It's a \"Song\"!!.OGG"
printf 'x'    > "$LIB/$A/notes.json"
printf 'x'    > "$LIB/$A/cover.jpg"
printf 'x'    > "$LIB/$A/.hidden.mp3"
printf 'ccc'  > "$LIB/Demo Album B/01 - Demo Song Two.flac"
printf 'd'    > "$LIB/Demo Album B/Disc 2/01 - Deep  Spaces.m4a"
printf 'ee'   > "$LIB/Démo Álbum Ç/01 - Canción.opus"
printf 'x'    > "$LIB/.hidden/01 - Hidden.mp3"
printf 'f'    > "$LIB/loose.wav"
printf 'g'    > "$LIB/also.aac"
printf 'x'    > "$LIB/index.json"

soloton_music_local_list "$LIB" > "$TMP/local"
expected_local="$(printf '%s\n' \
  "3	Demo Album B/01 - Demo Song Two.flac" \
  "1	Demo Album B/Disc 2/01 - Deep  Spaces.m4a" \
  "4	$A/01 - Demo Song One.mp3" \
  "2	$A/02 - It's a \"Song\"!!.OGG" \
  "2	Démo Álbum Ç/01 - Canción.opus" \
  "1	also.aac" \
  "1	loose.wav")"
eq "local list: audio only, sizes, sub-folders, awkward names" "$expected_local" "$(cat "$TMP/local")"
eq "trailing slash on the source does not matter" "$expected_local" "$(soloton_music_local_list "$LIB/")"

# ---------- music sync: what gets copied ----------
: > "$TMP/remote"
eq "empty device: everything is copied" "7" "$(soloton_music_plan "$TMP/local" "$TMP/remote" | wc -l | tr -d ' ')"

# The device already has two files right, one with the wrong size, and one extra file.
printf '%s\n' \
  "4 $A/01 - Demo Song One.mp3" \
  "2 Démo Álbum Ç/01 - Canción.opus" \
  "999 Demo Album B/01 - Demo Song Two.flac" \
  "5 Old Album/01 - Left Alone.mp3" > "$TMP/remote"
expected_copy="$(printf '%s\n' \
  "Demo Album B/01 - Demo Song Two.flac" \
  "Demo Album B/Disc 2/01 - Deep  Spaces.m4a" \
  "$A/02 - It's a \"Song\"!!.OGG" \
  "also.aac" \
  "loose.wav")"
eq "same size is skipped, different size and missing are copied" "$expected_copy" "$(soloton_music_plan "$TMP/local" "$TMP/remote")"

# Everything already there: nothing to copy.
sed 's/\t/ /' "$TMP/local" > "$TMP/remote"
eq "second sync copies nothing" "" "$(soloton_music_plan "$TMP/local" "$TMP/remote")"

# ---------- music sync: quoting for the device shell ----------
eq "shell quote: plain"       "'/sdcard/Music/soloton'" "$(soloton_shell_quote /sdcard/Music/soloton)"
eq "shell quote: apostrophe"  "'/m/It'\\''s!! \$HOME \`x\`'" "$(soloton_shell_quote "/m/It's!! \$HOME \`x\`")"
eq "shell quote round-trips"  "/m/It's \"here\"!! \$HOME * ;" "$(eval "printf '%s' $(soloton_shell_quote "/m/It's \"here\"!! \$HOME * ;")")"

# ---------- music sync: a fake adb, end to end ----------
# Stands in for adb: "shell" runs the command locally with /sdcard mapped into a temp dir; "push" copies.
FAKE="$TMP/fake-adb"
DEVICE="$TMP/device"
mkdir -p "$DEVICE"
cat > "$FAKE" <<FAKEADB
#!/usr/bin/env bash
[ "\$1" = "-s" ] && shift 2
cmd="\$1"; shift
echo "\$cmd" >> "$TMP/adb-calls"
case "\$cmd" in
  shell) cd "$DEVICE" && sh -c "\$(printf '%s' "\$1" | sed 's|/sdcard/|$DEVICE/sdcard/|g')" ;;
  push)
    args=("\$@"); n=\${#args[@]}; target="\${args[\$((n - 1))]}"
    target="$DEVICE\$target"
    [ -d "\$target" ] || { echo "no such dir \$target" >&2; exit 1; }
    for ((i = 0; i < n - 1; i++)); do cp "\${args[\$i]}" "\$target" || exit 1; done ;;
esac
FAKEADB
chmod +x "$FAKE"

: > "$TMP/adb-calls"
eq "first sync summary" "Music sync: 7 copied, 0 already there (fake-1:/sdcard/Music/soloton)" \
  "$(soloton_music_sync "$FAKE" fake-1 "$LIB" /sdcard/Music/soloton)"
eq "device has exactly the audio files" "$(cut -f2 "$TMP/local")" \
  "$(cd "$DEVICE/sdcard/Music/soloton" && find . -type f | sed 's|^\./||' | LC_ALL=C sort)"
eq "contents survive" "bb" "$(cat "$DEVICE/sdcard/Music/soloton/$A/02 - It's a \"Song\"!!.OGG")"
eq "one push per folder, not per file" "5" "$(grep -c '^push$' "$TMP/adb-calls")"

: > "$TMP/adb-calls"
eq "second sync summary" "Music sync: 0 copied, 7 already there (fake-1:/sdcard/Music/soloton)" \
  "$(soloton_music_sync "$FAKE" fake-1 "$LIB/" /sdcard/Music/soloton/)"
eq "second sync pushes nothing" "0" "$(grep -c '^push$' "$TMP/adb-calls")"

printf 'changed' > "$LIB/$A/01 - Demo Song One.mp3"
printf 'new' > "$LIB/Empty/01 - New Song.mp3"
eq "changed and new files only" "Music sync: 2 copied, 6 already there (fake-1:/sdcard/Music/soloton)" \
  "$(soloton_music_sync "$FAKE" fake-1 "$LIB" /sdcard/Music/soloton)"
eq "changed file was replaced" "changed" "$(cat "$DEVICE/sdcard/Music/soloton/$A/01 - Demo Song One.mp3")"

printf '\n%d passed, %d failed\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
